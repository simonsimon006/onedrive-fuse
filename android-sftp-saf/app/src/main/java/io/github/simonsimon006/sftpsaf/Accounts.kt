package io.github.simonsimon006.sftpsaf

import android.content.Context
import android.provider.DocumentsContract
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Authority of the DocumentsProvider; must match AndroidManifest.xml. */
const val AUTHORITY = "io.github.simonsimon006.sftpsaf.documents"

/**
 * One configured SFTP server. [hostKey] is the base64 SSH wire format of the
 * host's public key, pinned the first time the server is added; a later key
 * change makes the connection fail instead of silently trusting it.
 */
data class Account(
    val id: String,
    val label: String,
    val host: String,
    val port: Int,
    val user: String,
    val password: String?,
    val keyFile: String?,
    val keyPassphrase: String?,
    val root: String,
    val hostKey: String,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("label", label)
        put("host", host)
        put("port", port)
        put("user", user)
        put("password", password ?: JSONObject.NULL)
        put("keyFile", keyFile ?: JSONObject.NULL)
        put("keyPassphrase", keyPassphrase ?: JSONObject.NULL)
        put("root", root)
        put("hostKey", hostKey)
    }

    companion object {
        fun fromJson(o: JSONObject) = Account(
            id = o.getString("id"),
            label = o.getString("label"),
            host = o.getString("host"),
            port = o.getInt("port"),
            user = o.getString("user"),
            password = o.optNullableString("password"),
            keyFile = o.optNullableString("keyFile"),
            keyPassphrase = o.optNullableString("keyPassphrase"),
            root = normalizeRoot(o.getString("root")),
            hostKey = o.getString("hostKey"),
        )
    }
}

private fun JSONObject.optNullableString(name: String): String? =
    if (isNull(name)) null else getString(name)

/**
 * New servers store the server's own canonical path, but ones added by the
 * first build may carry a trailing slash. Child ids are derived from the root,
 * so "/srv/b/" and "/srv/b" would name the same folder twice and change
 * notifications for it would go to the wrong id.
 */
internal fun normalizeRoot(root: String): String =
    if (root.length > 1) root.trimEnd('/').ifEmpty { "/" } else root.ifEmpty { "." }

/**
 * Accounts live in app-private SharedPreferences. That is the app sandbox plus
 * whatever file-based encryption the device does; it is not a hardened secret
 * store, which the README says out loud.
 */
object Accounts {
    private const val PREFS = "servers"
    private const val KEY = "list"

    fun all(ctx: Context): List<Account> {
        val raw = prefs(ctx).getString(KEY, null) ?: return emptyList()
        val arr = JSONArray(raw)
        return (0 until arr.length()).map { Account.fromJson(arr.getJSONObject(it)) }
    }

    fun byId(ctx: Context, id: String): Account? = all(ctx).firstOrNull { it.id == id }

    fun add(ctx: Context, account: Account) {
        write(ctx, all(ctx).filterNot { it.id == account.id } + account)
    }

    fun remove(ctx: Context, id: String) {
        val gone = byId(ctx, id) ?: return
        gone.keyFile?.let { runCatching { File(it).delete() } }
        Sessions.close(id)
        write(ctx, all(ctx).filterNot { it.id == id })
    }

    fun newId(): String = UUID.randomUUID().toString().substring(0, 8)

    private fun write(ctx: Context, accounts: List<Account>) {
        val arr = JSONArray()
        accounts.forEach { arr.put(it.toJson()) }
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
        ctx.contentResolver.notifyChange(DocumentsContract.buildRootsUri(AUTHORITY), null)
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
