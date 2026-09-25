package io.github.simonsimon006.sftpsaf

import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.ProxyFileDescriptorCallback
import android.os.SystemClock
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
import java.io.BufferedOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
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
        val isRoot = isRoot(documentId)
        addDocumentRow(cursor, documentId, if (isRoot) account.label else nameOf(path), attributes, isRoot)
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
                addDocumentRow(cursor, docId(accountId, childPath), entry.name, attributes, isRoot = false)
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
        requireNotRoot(documentId)
        val path = pathOf(documentId)
        onSftp(documentId) { removeRecursively(it, path) }
        notifyChildrenChanged(docId(accountIdOf(documentId), parentOf(path)))
    }

    override fun removeDocument(documentId: String, parentDocumentId: String?) {
        deleteDocument(documentId)
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        requireNotRoot(documentId)
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
        requireNotRoot(sourceDocumentId)
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

    /**
     * Every open — including a write-only backup stream — is served as a proxy
     * descriptor, not a pipe. A pipe cannot be fsync'd, so an upload failure in
     * its last in-flight window could never reach the app; through the proxy the
     * app's fsync() waits for every write to be acknowledged and fails if one
     * was not. Writes are still pipelined, so this costs no throughput.
     */
    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        val flags = ParcelFileDescriptor.parseMode(mode)
        val readable = flags and ParcelFileDescriptor.MODE_READ_ONLY != 0
        val writable = flags and ParcelFileDescriptor.MODE_WRITE_ONLY != 0
        val path = pathOf(documentId)
        val opened = onSftp(documentId) { openRemote(it, path, flags) }

        val worker = HandlerThread("sftp-fd")
        worker.start()
        val wakeLock = TransferWakeLock(context!!)
        val callback = SftpFileCallback(
            opened.file,
            opened.chunk,
            opened.base,
            opened.visibleLength,
            wakeLock::renew,
        ) { wrote ->
            wakeLock.release()
            worker.quitSafely()
            if (wrote) {
                runCatching { notifyChildrenChanged(docId(accountIdOf(documentId), parentOf(path))) }
            }
        }
        val access = when {
            readable && writable -> ParcelFileDescriptor.MODE_READ_WRITE
            writable -> ParcelFileDescriptor.MODE_WRITE_ONLY
            else -> ParcelFileDescriptor.MODE_READ_ONLY
        }
        return try {
            context!!.getSystemService(StorageManager::class.java)
                .openProxyFileDescriptor(access, callback, Handler(worker.looper))
        } catch (e: Exception) {
            worker.quitSafely()
            wakeLock.release()
            runCatching { opened.file.close() }
            throw if (e is IOException) IllegalStateException("cannot open $path", e) else e
        }
    }

    private fun addDocumentRow(
        cursor: MatrixCursor,
        documentId: String,
        name: String,
        attributes: FileAttributes,
        isRoot: Boolean,
    ) {
        val isDirectory = attributes.type == FileMode.Type.DIRECTORY
        var flags = if (isDirectory) Document.FLAG_DIR_SUPPORTS_CREATE else Document.FLAG_SUPPORTS_WRITE
        if (!isRoot) {
            flags = flags or Document.FLAG_SUPPORTS_DELETE or
                Document.FLAG_SUPPORTS_RENAME or
                Document.FLAG_SUPPORTS_MOVE
        }
        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, documentId)
            add(Document.COLUMN_DISPLAY_NAME, name)
            add(
                Document.COLUMN_MIME_TYPE,
                if (isDirectory) Document.MIME_TYPE_DIR else mimeTypeOf(name),
            )
            // A directory's own size is filesystem bookkeeping, not something to show.
            add(Document.COLUMN_SIZE, if (isDirectory) null else attributes.size)
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

    /**
     * The root is the folder the whole location hangs off. Deleting or moving it
     * through a tree grant would wipe or orphan everything below, so the flags
     * never offer it and this refuses it for clients that ignore the flags.
     */
    private fun requireNotRoot(documentId: String) {
        check(!isRoot(documentId)) {
            "The server's root folder cannot be deleted, renamed or moved from here."
        }
    }

    // Normalised, because a tree grant made with the first build can still carry
    // the root spelled with a trailing slash.
    private fun isRoot(documentId: String): Boolean =
        normalizeRoot(pathOf(documentId)) == account(documentId).root

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

internal class OpenedFile(
    val file: RemoteFile,
    /** Largest payload for one SFTP write. */
    val chunk: Int,
    /** Remote offset of the descriptor's offset 0. */
    val base: Long,
    /** Length the descriptor reports to begin with. */
    val visibleLength: Long,
)

/**
 * Opens [path] for a SAF mode. The proxy descriptor only carries the access mode
 * (the system masks off O_APPEND and O_TRUNC before AppFuse sees them), so both
 * are done here: truncation by the SFTP open, and appending by showing the
 * descriptor an empty file whose offset 0 sits at the current end.
 */
internal fun openRemote(sftp: SFTPClient, path: String, flags: Int): OpenedFile {
    val readable = flags and ParcelFileDescriptor.MODE_READ_ONLY != 0
    val writable = flags and ParcelFileDescriptor.MODE_WRITE_ONLY != 0
    val append = writable && flags and ParcelFileDescriptor.MODE_APPEND != 0
    // Since Android 10 plain "w" no longer carries MODE_TRUNCATE, only "wt" does.
    // A write-only descriptor cannot read, so it can only be rewriting the file;
    // keeping the old tail would leave a shorter new backup with the previous
    // one's end still attached — for a zip, the old central directory. So "w"
    // truncates as it used to, and only "rw" needs the explicit "t".
    val truncate = writable && !append &&
        (!readable || flags and ParcelFileDescriptor.MODE_TRUNCATE != 0)

    val modes = EnumSet.noneOf(OpenMode::class.java)
    if (readable) modes.add(OpenMode.READ)
    if (writable) {
        modes.add(OpenMode.WRITE)
        modes.add(OpenMode.CREAT)
        if (truncate) modes.add(OpenMode.TRUNC)
    }
    val file = sftp.open(path, modes)
    try {
        val length = if (truncate) 0L else file.length()
        // Largest payload that fits one SSH packet, and never above the 32 KiB
        // every SFTP server has to accept.
        val chunk = (sftp.sftpEngine.subsystem.remoteMaxPacketSize - file.outgoingPacketOverhead)
            .coerceIn(1024, 32 * 1024)
        return OpenedFile(file, chunk, if (append) length else 0L, if (append) 0L else length)
    } catch (e: IOException) {
        runCatching { file.close() }
        throw e
    }
}

/**
 * Serves one proxy descriptor from an open remote file. Every call arrives on the
 * descriptor's own handler thread, so none of this state needs locking.
 *
 * Writes are pipelined: up to [MAX_UNCONFIRMED_WRITES] chunks are in flight, so a
 * failure usually surfaces on a later write() rather than the one that caused it,
 * and at the latest on fsync(), which waits for every outstanding reply. Only
 * close() cannot report anything — AppFuse has no flush hook — so an app that
 * closes without fsync can miss an error in its last ~1 MB, as it would on NFS.
 *
 * Sequential reads stream through one read-ahead window; a seek, or any write,
 * drops it and the next read starts a new one.
 */
internal class SftpFileCallback(
    private val file: RemoteFile,
    private val chunk: Int,
    /** Remote offset of this descriptor's offset 0; non-zero only when appending. */
    private val base: Long,
    /** File length as this descriptor sees it, kept current by our own writes. */
    private var length: Long,
    /** Called before each read, write or fsync — keeps the wake lock alive. */
    private val onActivity: () -> Unit,
    private val onClosed: (wrote: Boolean) -> Unit,
) : ProxyFileDescriptorCallback() {

    private var reader: InputStream? = null
    private var readerOffset = -1L
    private var writer: OutputStream? = null
    private var writerOffset = -1L
    private var writeFailure: IOException? = null
    private var wrote = false

    // Answered from memory: the kernel asks on every getattr, and a round trip
    // each time would stall the write pipeline.
    override fun onGetSize(): Long = length

    override fun onRead(offset: Long, size: Int, data: ByteArray): Int = io {
        // A read must see what this descriptor already wrote.
        if (writer != null) writing { flushWriter() }
        val current = reader
        val source = if (current != null && readerOffset == offset) {
            current
        } else {
            readerOffset = offset
            file.ReadAheadRemoteFileInputStream(MAX_UNCONFIRMED_READS, base + offset)
                .also { reader = it }
        }
        var got = 0
        while (got < size) {
            val read = source.read(data, got, size - got)
            if (read < 0) break
            got += read
        }
        readerOffset += got
        got
    }

    override fun onWrite(offset: Long, size: Int, data: ByteArray): Int = io {
        writing {
            reader = null
            val sink = writer?.takeIf { writerOffset == offset } ?: run {
                flushWriter()
                // Buffered so a client writing in small pieces still fills whole packets.
                BufferedOutputStream(file.RemoteFileOutputStream(base + offset, MAX_UNCONFIRMED_WRITES), chunk)
                    .also {
                        writer = it
                        writerOffset = offset
                    }
            }
            // AppFuse hands over up to 128 KiB at once; no single SFTP write may exceed a chunk.
            var done = 0
            while (done < size) {
                val piece = minOf(chunk, size - done)
                sink.write(data, done, piece)
                done += piece
            }
            writerOffset += size
            length = maxOf(length, offset + size)
            wrote = true
        }
        size
    }

    override fun onFsync() = io { writing { flushWriter() } }

    override fun onRelease() {
        if (writer != null) {
            runCatching { writing { flushWriter() } }.onFailure {
                Log.e(TAG, "write failed after the client closed; it could not be told", it)
            }
        }
        reader = null
        runCatching { file.close() }
        onClosed(wrote)
    }

    /**
     * Runs a step of the write pipeline. The first failure is sticky: the failed
     * reply has been consumed by the time it is reported, so without this a later
     * fsync would find nothing outstanding and report success for a file that is
     * missing data.
     */
    private inline fun <T> writing(block: () -> T): T {
        writeFailure?.let { throw IOException("an earlier write to this file failed", it) }
        try {
            return block()
        } catch (e: Exception) {
            val failure = e as? IOException ?: IOException(e)
            writeFailure = failure
            writer = null
            writerOffset = -1L
            throw failure
        }
    }

    /** Pushes buffered bytes out and waits until the server has acknowledged all of them. */
    private fun flushWriter() {
        val sink = writer ?: return
        writer = null
        writerOffset = -1L
        sink.flush()
    }

    private inline fun <T> io(block: () -> T): T {
        onActivity()
        return try {
            block()
        } catch (e: IOException) {
            Log.e(TAG, "remote file operation failed", e)
            throw ErrnoException("sftp", OsConstants.EIO, e)
        }
    }
}

/**
 * Keeps the CPU awake while a descriptor is moving bytes, so a multi-hour backup
 * survives the screen going off, but lets go within a minute of the last read or
 * write. An app that leaves a descriptor open and idle therefore does not hold
 * the device awake, and a descriptor that is never closed cannot leak the lock.
 */
private class TransferWakeLock(context: Context) {
    private val lock = context.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$TAG:transfer")
        .apply { setReferenceCounted(false) }
    private var renewedAt = 0L

    fun renew() {
        val now = SystemClock.elapsedRealtime()
        // Renewing is a binder call; once every few seconds is plenty.
        if (!lock.isHeld || now - renewedAt >= WAKE_LOCK_RENEW_MS) {
            lock.acquire(WAKE_LOCK_HOLD_MS)
            renewedAt = now
        }
    }

    fun release() {
        runCatching { lock.release() }
    }
}

private const val WAKE_LOCK_HOLD_MS = 60_000L
private const val WAKE_LOCK_RENEW_MS = 15_000L
