package io.github.simonsimon006.sftpsaf

import android.util.Base64
import android.util.Log
import net.schmizz.keepalive.KeepAliveProvider
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.common.SecurityUtils
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.IOException
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Security
import java.util.concurrent.ConcurrentHashMap

const val TAG = "SftpSaf"

private const val CONNECT_TIMEOUT_MS = 20_000

/**
 * SFTP requests wait this long for their reply. It has to cover the slowest
 * link someone might back up over: with [MAX_UNCONFIRMED_WRITES] chunks in
 * flight the oldest one is only acknowledged after everything ahead of it has
 * gone out, which on a slow uplink is minutes, not seconds.
 */
private const val SFTP_TIMEOUT_MS = 5 * 60_000

private const val KEEP_ALIVE_SECONDS = 30

/** Write requests allowed in flight at once; enough to fill a fat, high-latency link. */
const val MAX_UNCONFIRMED_WRITES = 32

/** Read-ahead requests allowed in flight at once. */
const val MAX_UNCONFIRMED_READS = 16

/**
 * Android bundles a cut-down BouncyCastle under the provider name "BC". sshj
 * finds it, decides BouncyCastle is present and then asks for algorithms the
 * stub does not have. The classes do not actually collide (Android's live in
 * com.android.org.bouncycastle), so swapping the registration for the real one
 * is enough.
 */
private fun initSecurity() {
    synchronized(Security::class.java) {
        val registered = Security.getProvider(BouncyCastleProvider.PROVIDER_NAME)
        if (registered == null || registered.javaClass != BouncyCastleProvider::class.java) {
            Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
            Security.addProvider(BouncyCastleProvider())
        }
        SecurityUtils.setSecurityProvider(SecurityUtils.BOUNCY_CASTLE)
    }
}

/** Base64 of a public key in SSH wire format — what gets pinned per server. */
fun hostKeyBlob(key: PublicKey): String {
    val buf = Buffer.PlainBuffer()
    KeyType.fromKey(key).putPubKeyIntoBuffer(key, buf)
    return Base64.encodeToString(buf.compactData, Base64.NO_WRAP)
}

/** The OpenSSH-style `SHA256:...` fingerprint of a pinned key, for showing to a human. */
fun fingerprintOf(blob: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(Base64.decode(blob, Base64.NO_WRAP))
    return "SHA256:" + Base64.encodeToString(digest, Base64.NO_WRAP).trimEnd('=')
}

/** Accepts exactly the key that was pinned when the server was added. */
class PinnedHostKey(private val pinned: String) : HostKeyVerifier {
    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean =
        MessageDigest.isEqual(
            Base64.decode(pinned, Base64.NO_WRAP),
            Base64.decode(hostKeyBlob(key), Base64.NO_WRAP),
        )

    /**
     * Tells sshj which host key algorithm we already trust, so the server does
     * not hand us a different key type on a later connection and trip the pin.
     */
    override fun findExistingAlgorithms(hostname: String, port: Int): List<String> {
        val type = runCatching {
            Buffer.PlainBuffer(Base64.decode(pinned, Base64.NO_WRAP)).readString()
        }.getOrNull() ?: return emptyList()
        // The same RSA key is used under all three signature algorithms.
        return if (type == "ssh-rsa") listOf("rsa-sha2-512", "rsa-sha2-256", "ssh-rsa")
        else listOf(type)
    }
}

/** Trust-on-first-use: records the key so the user can confirm its fingerprint. */
class LearningHostKey : HostKeyVerifier {
    @Volatile
    var seen: String? = null

    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
        seen = hostKeyBlob(key)
        return true
    }

    override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
}

fun connect(account: Account, verifier: HostKeyVerifier): SSHClient {
    initSecurity()
    val config = DefaultConfig()
    config.keepAliveProvider = KeepAliveProvider.KEEP_ALIVE
    val client = SSHClient(config)
    client.connectTimeout = CONNECT_TIMEOUT_MS
    client.addHostKeyVerifier(verifier)
    // sshj starts the keep-alive thread inside connect(), and only if the
    // interval is already set; setting it afterwards silently does nothing.
    client.connection.keepAlive.keepAliveInterval = KEEP_ALIVE_SECONDS
    try {
        client.connect(account.host, account.port)
        if (account.keyFile != null) {
            val keys = if (account.keyPassphrase.isNullOrEmpty()) {
                client.loadKeys(account.keyFile)
            } else {
                client.loadKeys(account.keyFile, account.keyPassphrase)
            }
            client.authPublickey(account.user, keys)
        } else {
            // Also answers keyboard-interactive password prompts, which is all some
            // servers (FreeBSD, TrueNAS CORE) accept.
            client.authPassword(account.user, account.password ?: "")
        }
    } catch (e: Throwable) {
        runCatching { client.close() }
        throw e
    }
    return client
}

/**
 * One SSH connection per configured server, shared by every SAF call. sshj
 * serialises requests on the channel itself, so browsing while a big upload
 * runs is fine.
 */
class SftpSession(private val account: Account) {
    // Written under the lock, but alive() reads them from whichever thread's
    // request just failed.
    @Volatile
    private var ssh: SSHClient? = null

    @Volatile
    private var sftp: SFTPClient? = null

    private fun alive(): Boolean {
        val c = ssh ?: return false
        return c.isConnected && c.isAuthenticated
    }

    /**
     * The live connection, dialling if needed. Callers that stream (the upload
     * path) use this directly: replaying a half-drained pipe after a reconnect
     * would silently corrupt the file, so they must fail instead of retrying.
     */
    @Synchronized
    fun connected(): SFTPClient {
        sftp?.let { if (alive()) return it }
        close()
        val c = connect(account, PinnedHostKey(account.hostKey))
        val s = c.newSFTPClient()
        s.sftpEngine.timeoutMs = SFTP_TIMEOUT_MS
        ssh = c
        sftp = s
        return s
    }

    /** Runs [block] against the connection, reconnecting once if it had gone away. */
    fun <T> exec(block: (SFTPClient) -> T): T {
        val first = connected()
        try {
            return block(first)
        } catch (e: IOException) {
            if (alive()) throw e
            Log.w(TAG, "connection to ${account.host} lost, retrying", e)
        }
        close()
        return block(connected())
    }

    @Synchronized
    fun close() {
        runCatching { sftp?.close() }
        runCatching { ssh?.close() }
        sftp = null
        ssh = null
    }
}

object Sessions {
    private val sessions = ConcurrentHashMap<String, SftpSession>()

    fun of(account: Account): SftpSession =
        sessions.computeIfAbsent(account.id) { SftpSession(account) }

    /**
     * Disconnecting writes to the socket, so it cannot happen on the main thread —
     * and this is reached from the UI when a server is removed.
     */
    fun close(accountId: String) {
        val session = sessions.remove(accountId) ?: return
        Thread({ session.close() }, "sftp-disconnect").start()
    }

    fun closeAll() {
        sessions.keys.toList().forEach { close(it) }
    }
}
