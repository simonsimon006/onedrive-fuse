package io.github.simonsimon006.sftpsaf

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import java.io.File

private const val REQUEST_KEY_FILE = 1

/** Two-part screen: a form that adds a server, and the list of the ones already added. */
class MainActivity : Activity() {

    private lateinit var label: EditText
    private lateinit var host: EditText
    private lateinit var port: EditText
    private lateinit var user: EditText
    private lateinit var root: EditText
    private lateinit var password: EditText
    private lateinit var passphrase: EditText
    private lateinit var pickKey: Button
    private lateinit var authKey: RadioButton
    private lateinit var add: Button
    private lateinit var status: TextView
    private lateinit var accounts: LinearLayout

    private var keyFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        label = findViewById(R.id.label)
        host = findViewById(R.id.host)
        port = findViewById(R.id.port)
        user = findViewById(R.id.user)
        root = findViewById(R.id.root)
        password = findViewById(R.id.password)
        passphrase = findViewById(R.id.passphrase)
        pickKey = findViewById(R.id.pickKey)
        authKey = findViewById(R.id.authKey)
        add = findViewById(R.id.add)
        status = findViewById(R.id.status)
        accounts = findViewById(R.id.accounts)

        findViewById<android.widget.RadioGroup>(R.id.auth).setOnCheckedChangeListener { _, checked ->
            val usingKey = checked == R.id.authKey
            password.visibility = if (usingKey) View.GONE else View.VISIBLE
            pickKey.visibility = if (usingKey) View.VISIBLE else View.GONE
            passphrase.visibility = if (usingKey) View.VISIBLE else View.GONE
        }

        pickKey.setOnClickListener {
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("*/*"),
                REQUEST_KEY_FILE,
            )
        }

        add.setOnClickListener { addServer() }
        showAccounts()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri: Uri = data?.data ?: return
        if (requestCode != REQUEST_KEY_FILE || resultCode != RESULT_OK) return
        try {
            val keys = File(filesDir, "keys").apply { mkdirs() }
            val target = File(keys, "${System.currentTimeMillis()}.key")
            contentResolver.openInputStream(uri).use { input ->
                target.outputStream().use { output -> input!!.copyTo(output) }
            }
            keyFile = target
            pickKey.text = "Private key loaded"
        } catch (e: Exception) {
            toast("Could not read the key: ${e.message}")
        }
    }

    private fun addServer() {
        val usingKey = authKey.isChecked
        val draft = Account(
            id = Accounts.newId(),
            label = label.text.toString().trim().ifEmpty { host.text.toString().trim() },
            host = host.text.toString().trim(),
            port = port.text.toString().trim().toIntOrNull() ?: 22,
            user = user.text.toString().trim(),
            password = if (usingKey) null else password.text.toString(),
            keyFile = if (usingKey) keyFile?.absolutePath else null,
            keyPassphrase = if (usingKey) passphrase.text.toString() else null,
            root = root.text.toString().trim().ifEmpty { "/" },
            hostKey = "",
        )
        if (draft.host.isEmpty() || draft.user.isEmpty()) {
            toast("Host and user are required")
            return
        }
        if (usingKey && draft.keyFile == null) {
            toast("Choose a private key file first")
            return
        }

        add.isEnabled = false
        status.text = "Connecting to ${draft.host}..."
        Thread {
            val learned = LearningHostKey()
            val outcome = runCatching {
                connect(draft, learned).use { client ->
                    client.newSFTPClient().use { sftp -> sftp.ls(draft.root) }
                }
                learned.seen ?: error("the server presented no host key")
            }
            runOnUiThread {
                add.isEnabled = true
                outcome.fold(
                    onSuccess = { blob ->
                        status.text = ""
                        confirmHostKey(draft.copy(hostKey = blob))
                    },
                    onFailure = { status.text = "Failed: ${it.message}" },
                )
            }
        }.start()
    }

    /**
     * Trust on first use, but with the fingerprint in front of the person doing
     * the trusting. After this the key is pinned and a change is a hard failure.
     */
    private fun confirmHostKey(account: Account) {
        AlertDialog.Builder(this)
            .setTitle("Trust this host key?")
            .setMessage(
                "${account.host}:${account.port}\n\n${fingerprintOf(account.hostKey)}\n\n" +
                    "Compare it with `ssh-keyscan -p ${account.port} ${account.host} | ssh-keygen -lf -` " +
                    "on a machine you trust. It is pinned from now on.",
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Trust") { _, _ ->
                Accounts.add(this, account)
                keyFile = null
                pickKey.text = "Choose private key file"
                listOf(label, host, user, password, passphrase).forEach { it.text.clear() }
                showAccounts()
                toast("${account.label} added — open it from any file picker")
            }
            .show()
    }

    private fun showAccounts() {
        accounts.removeAllViews()
        val configured = Accounts.all(this)
        if (configured.isEmpty()) {
            accounts.addView(TextView(this).apply { text = "None yet." })
            return
        }
        for (account in configured) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(
                TextView(this).apply {
                    text = "${account.label}\n${account.user}@${account.host}:${account.port}" +
                        "${account.root}\n${fingerprintOf(account.hostKey)}"
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                },
            )
            row.addView(
                Button(this).apply {
                    text = "Remove"
                    setOnClickListener {
                        Accounts.remove(this@MainActivity, account.id)
                        showAccounts()
                    }
                },
            )
            accounts.addView(row)
        }
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
