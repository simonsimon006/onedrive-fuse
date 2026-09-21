package io.github.simonsimon006.sftpsaf

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.system.ErrnoException
import android.system.OsConstants
import android.util.Log
import android.webkit.MimeTypeMap
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.RemoteFile
import net.schmizz.sshj.sftp.Response
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.SFTPException
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.util.EnumSet

private val DEFAULT_ROOT_PROJECTION = arrayOf(
    Root.COLUMN_ROOT_ID,
    Root.COLUMN_DOCUMENT_ID,
    Root.COLUMN_TITLE,
    Root.COLUMN_SUMMARY,
    Root.COLUMN_FLAGS,
    Root.COLUMN_ICON,
)

private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
    Document.COLUMN_DOCUMENT_ID,
    Document.COLUMN_DISPLAY_NAME,
    Document.COLUMN_MIME_TYPE,
    Document.COLUMN_SIZE,
    Document.COLUMN_LAST_MODIFIED,
    Document.COLUMN_FLAGS,
)

/** Document ids are `<account id>:<absolute remote path>`. */
internal fun docId(accountId: String, path: String) = "$accountId:$path"

internal fun accountIdOf(documentId: String): String = documentId.substringBefore(':')

internal fun pathOf(documentId: String): String = documentId.substringAfter(':')

internal fun joinPath(dir: String, name: String) =
    if (dir.endsWith("/")) dir + name else "$dir/$name"

internal fun parentOf(path: String): String {
    val trimmed = path.trimEnd('/')
    val cut = trimmed.lastIndexOf('/')
    return if (cut <= 0) "/" else trimmed.substring(0, cut)
}

internal fun nameOf(path: String): String =
    path.trimEnd('/').substringAfterLast('/').ifEmpty { "/" }

class SftpProvider : DocumentsProvider() {

    override fun onCreate(): Boolean = true

    override fun shutdown() {
        Sessions.closeAll()
    }

    override fun queryRoots(projection: Array<String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
        for (account in Accounts.all(context!!)) {
            cursor.newRow().apply {
                add(Root.COLUMN_ROOT_ID, account.id)
                add(Root.COLUMN_DOCUMENT_ID, docId(account.id, account.root))
                add(Root.COLUMN_TITLE, account.label)
                add(Root.COLUMN_SUMMARY, "${account.user}@${account.host}:${account.root}")
                add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD)
                add(Root.COLUMN_ICON, R.drawable.ic_launcher)
            }
        }
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val account = account(documentId)
        val path = pathOf(documentId)
        val attributes = onSftp(documentId) { it.stat(path) }
        val name = if (path == account.root) account.label else nameOf(path)
        addDocumentRow(cursor, documentId, name, attributes)
        return cursor
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<String>?,
        sortOrder: String?,
    ): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val accountId = accountIdOf(parentDocumentId)
        val parent = pathOf(parentDocumentId)
        onSftp(parentDocumentId) { sftp ->
            for (entry in sftp.ls(parent)) {
                if (entry.name == "." || entry.name == "..") continue
                val childPath = joinPath(parent, entry.name)
                var attributes = entry.attributes
                if (attributes.type == FileMode.Type.SYMLINK) {
                    // READDIR reports the link itself; resolve so linked directories
                    // are browsable. A dangling link just stays a link.
                    attributes = runCatching { sftp.stat(childPath) }.getOrDefault(attributes)
                }
                addDocumentRow(cursor, docId(accountId, childPath), entry.name, attributes)
            }
        }
        cursor.setNotificationUri(
            context!!.contentResolver,
            DocumentsContract.buildChildDocumentsUri(AUTHORITY, parentDocumentId),
        )
        return cursor
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        if (accountIdOf(parentDocumentId) != accountIdOf(documentId)) return false
        val parent = pathOf(parentDocumentId).trimEnd('/')
        return pathOf(documentId).startsWith("$parent/")
    }

    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String,
    ): String {
        val accountId = accountIdOf(parentDocumentId)
        val parent = pathOf(parentDocumentId)
        val created = onSftp(parentDocumentId) { sftp ->
            val path = freePath(sftp, parent, displayName)
            if (Document.MIME_TYPE_DIR == mimeType) {
                sftp.mkdir(path)
            } else {
                sftp.open(path, EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.EXCL)).close()
            }
            path
        }
        notifyChildrenChanged(parentDocumentId)
        return docId(accountId, created)
    }

    override fun deleteDocument(documentId: String) {
        val path = pathOf(documentId)
        onSftp(documentId) { removeRecursively(it, path) }
        notifyChildrenChanged(docId(accountIdOf(documentId), parentOf(path)))
    }

    override fun removeDocument(documentId: String, parentDocumentId: String?) {
        deleteDocument(documentId)
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val path = pathOf(documentId)
        val parent = parentOf(path)
        if (displayName == nameOf(path)) return documentId
        val renamed = onSftp(documentId) { sftp ->
            val target = freePath(sftp, parent, displayName)
            sftp.rename(path, target)
            target
        }
        notifyChildrenChanged(docId(accountIdOf(documentId), parent))
        return docId(accountIdOf(documentId), renamed)
    }

    override fun moveDocument(
        sourceDocumentId: String,
        sourceParentDocumentId: String,
        targetParentDocumentId: String,
    ): String {
        check(accountIdOf(sourceDocumentId) == accountIdOf(targetParentDocumentId)) {
            "Moving between two different servers is not supported; copy the file instead."
        }
        val from = pathOf(sourceDocumentId)
        val moved = onSftp(sourceDocumentId) { sftp ->
            val target = freePath(sftp, pathOf(targetParentDocumentId), nameOf(from))
            sftp.rename(from, target)
            target
        }
        notifyChildrenChanged(sourceParentDocumentId)
        notifyChildrenChanged(targetParentDocumentId)
        return docId(accountIdOf(sourceDocumentId), moved)
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        val flags = ParcelFileDescriptor.parseMode(mode)
        val readable = flags and ParcelFileDescriptor.MODE_READ_ONLY != 0
        val writable = flags and ParcelFileDescriptor.MODE_WRITE_ONLY != 0
        val append = flags and ParcelFileDescriptor.MODE_APPEND != 0
        val truncate = flags and ParcelFileDescriptor.MODE_TRUNCATE != 0
        return if (writable && !readable) {
            openStreamingWrite(documentId, append, truncate, signal)
        } else {
            openRandomAccess(documentId, writable, truncate)
        }
    }

    /**
     * Write-only opens — which is what a backup writing one enormous file does —
     * are served by a pipe drained straight into the SFTP channel. Nothing is
     * staged on local storage and memory stays flat, so file size is bounded
     * only by the remote filesystem. The cost is that the stream is one-way:
     * the client cannot seek, which is why read/write opens take the slower
     * random-access path below.
     */
    private fun openStreamingWrite(
        documentId: String,
        append: Boolean,
        truncate: Boolean,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        val path = pathOf(documentId)
        val session = Sessions.of(account(documentId))
        val pipe = try {
            ParcelFileDescriptor.createReliablePipe()
        } catch (e: IOException) {
            throw IllegalStateException("cannot open pipe for $path", e)
        }
        val readSide = pipe[0]
        val writeSide = pipe[1]

        signal?.setOnCancelListener {
            runCatching { readSide.closeWithError("cancelled") }
        }

        val wakeLock = context!!.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$TAG:upload")
        wakeLock.acquire()

        Thread({
            var failure: String? = null
            try {
                upload(session.connected(), readSide, path, append, truncate)
            } catch (t: Throwable) {
                failure = t.message ?: t.javaClass.simpleName
                Log.e(TAG, "upload to $path failed", t)
            } finally {
                // A reliable pipe lets us fail the client's write/close instead of
                // letting it believe a half-finished upload succeeded.
                runCatching {
                    if (failure != null) readSide.closeWithError(failure) else readSide.close()
                }
                runCatching { wakeLock.release() }
                runCatching {
                    notifyChildrenChanged(docId(accountIdOf(documentId), parentOf(path)))
                }
            }
        }, "sftp-upload").start()

        return writeSide
    }

    private fun upload(
        sftp: SFTPClient,
        readSide: ParcelFileDescriptor,
        path: String,
        append: Boolean,
        truncate: Boolean,
    ) {
        val modes = EnumSet.of(OpenMode.WRITE, OpenMode.CREAT)
        if (truncate && !append) modes.add(OpenMode.TRUNC)
        sftp.open(path, modes).use { file ->
            val offset = if (append) file.length() else 0L
            val chunk = (sftp.sftpEngine.subsystem.remoteMaxPacketSize - file.outgoingPacketOverhead)
                .coerceIn(1024, 64 * 1024)
            // readSide owns the descriptor, so this stream is deliberately not closed.
            val source = FileInputStream(readSide.fileDescriptor)
            file.RemoteFileOutputStream(offset, MAX_UNCONFIRMED_WRITES).use { sink ->
                val buffer = ByteArray(chunk)
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    sink.write(buffer, 0, read)
                }
            }
        }
    }

    /**
     * Reads (and read/write opens) go through a proxy descriptor so clients can
     * seek. Sequential access still streams with read-ahead; only an actual seek
     * pays for a new request chain.
     */
    private fun openRandomAccess(
        documentId: String,
        writable: Boolean,
        truncate: Boolean,
    ): ParcelFileDescriptor {
        val path = pathOf(documentId)
        val modes = EnumSet.of(OpenMode.READ)
        if (writable) {
            modes.add(OpenMode.WRITE)
            modes.add(OpenMode.CREAT)
            // "rwt" asks for the old contents to go away; without this the tail of
            // a longer previous file would survive underneath the new one.
            if (truncate) modes.add(OpenMode.TRUNC)
        }
        val file = onSftp(documentId) { it.open(path, modes) }

        val worker = HandlerThread("sftp-fd")
        worker.start()
        val wakeLock = context!!.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$TAG:transfer")
        wakeLock.acquire()

        val callback = SftpFileCallback(file) {
            runCatching { worker.quitSafely() }
            runCatching { wakeLock.release() }
            if (writable) {
                runCatching {
                    notifyChildrenChanged(docId(accountIdOf(documentId), parentOf(path)))
                }
            }
        }
        return try {
            context!!.getSystemService(StorageManager::class.java).openProxyFileDescriptor(
                if (writable) ParcelFileDescriptor.MODE_READ_WRITE else ParcelFileDescriptor.MODE_READ_ONLY,
                callback,
                Handler(worker.looper),
            )
        } catch (e: IOException) {
            worker.quitSafely()
            wakeLock.release()
            runCatching { file.close() }
            throw IllegalStateException("cannot open $path", e)
        }
    }

    private fun addDocumentRow(
        cursor: MatrixCursor,
        documentId: String,
        name: String,
        attributes: FileAttributes,
    ) {
        val isDirectory = attributes.type == FileMode.Type.DIRECTORY
        var flags = Document.FLAG_SUPPORTS_DELETE or
            Document.FLAG_SUPPORTS_RENAME or
            Document.FLAG_SUPPORTS_MOVE
        flags = flags or if (isDirectory) Document.FLAG_DIR_SUPPORTS_CREATE
        else Document.FLAG_SUPPORTS_WRITE
        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, documentId)
            add(Document.COLUMN_DISPLAY_NAME, name)
            add(
                Document.COLUMN_MIME_TYPE,
                if (isDirectory) Document.MIME_TYPE_DIR else mimeTypeOf(name),
            )
            add(Document.COLUMN_SIZE, attributes.size)
            add(Document.COLUMN_LAST_MODIFIED, attributes.mtime * 1000L)
            add(Document.COLUMN_FLAGS, flags)
        }
    }

    private fun mimeTypeOf(name: String): String {
        val extension = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: "application/octet-stream"
    }

    private fun removeRecursively(sftp: SFTPClient, path: String) {
        // lstat, not stat: deleting a symlink must not follow it.
        if (sftp.lstat(path).type == FileMode.Type.DIRECTORY) {
            for (entry in sftp.ls(path)) {
                if (entry.name == "." || entry.name == "..") continue
                removeRecursively(sftp, joinPath(path, entry.name))
            }
            sftp.rmdir(path)
        } else {
            sftp.rm(path)
        }
    }

    /** Picks a free name in [dir], appending " (1)", " (2)", ... the way SAF expects. */
    private fun freePath(sftp: SFTPClient, dir: String, displayName: String): String {
        val cleaned = displayName.replace('/', '_').trim().ifEmpty { "unnamed" }
        val dot = cleaned.lastIndexOf('.')
        val stem = if (dot > 0) cleaned.substring(0, dot) else cleaned
        val extension = if (dot > 0) cleaned.substring(dot) else ""
        var candidate = cleaned
        var attempt = 1
        while (sftp.statExistence(joinPath(dir, candidate)) != null) {
            candidate = "$stem ($attempt)$extension"
            attempt++
        }
        return joinPath(dir, candidate)
    }

    private fun account(documentId: String): Account =
        Accounts.byId(context!!, accountIdOf(documentId))
            ?: throw FileNotFoundException("no server configured for $documentId")

    private fun notifyChildrenChanged(parentDocumentId: String) {
        context!!.contentResolver.notifyChange(
            DocumentsContract.buildChildDocumentsUri(AUTHORITY, parentDocumentId),
            null,
        )
    }

    /** Runs an SFTP call and maps its failures onto what SAF understands. */
    private fun <T> onSftp(documentId: String, block: (SFTPClient) -> T): T {
        val path = pathOf(documentId)
        try {
            return Sessions.of(account(documentId)).exec(block)
        } catch (e: SFTPException) {
            if (e.statusCode == Response.StatusCode.NO_SUCH_FILE ||
                e.statusCode == Response.StatusCode.NO_SUCH_PATH
            ) {
                throw FileNotFoundException("$path: ${e.message}")
            }
            throw IllegalStateException("$path: ${e.message}", e)
        } catch (e: IOException) {
            throw IllegalStateException("$path: ${e.message}", e)
        }
    }
}

/**
 * Serves a proxy descriptor from an open remote file. Sequential reads reuse one
 * read-ahead stream; a seek throws it away and starts a new one at the new offset.
 */
private class SftpFileCallback(
    private val file: RemoteFile,
    private val onClosed: () -> Unit,
) : ProxyFileDescriptorCallback() {

    private var stream: InputStream? = null
    private var streamOffset = -1L

    override fun onGetSize(): Long = guarded { file.length() }

    override fun onRead(offset: Long, size: Int, data: ByteArray): Int = guarded {
        val current = stream
        val source = if (current != null && streamOffset == offset) {
            current
        } else {
            runCatching { current?.close() }
            streamOffset = offset
            file.ReadAheadRemoteFileInputStream(MAX_UNCONFIRMED_READS, offset).also { stream = it }
        }
        var got = 0
        while (got < size) {
            val read = source.read(data, got, size - got)
            if (read < 0) break
            got += read
        }
        streamOffset += got
        got
    }

    override fun onWrite(offset: Long, size: Int, data: ByteArray): Int = guarded {
        dropStream()
        file.write(offset, data, 0, size)
        size
    }

    override fun onFsync() = Unit

    override fun onRelease() {
        dropStream()
        runCatching { file.close() }
        onClosed()
    }

    private fun dropStream() {
        runCatching { stream?.close() }
        stream = null
        streamOffset = -1L
    }

    private inline fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (e: IOException) {
        Log.e(TAG, "remote file operation failed", e)
        throw ErrnoException("sftp", OsConstants.EIO, e)
    }
}
