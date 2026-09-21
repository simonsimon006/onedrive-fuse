# SFTP Storage

An Android [`DocumentsProvider`](https://developer.android.com/reference/android/provider/DocumentsProvider)
that plugs an SFTP server into the system Storage Access Framework. Once a server is
added it shows up as a location in the Files app and in every app's file picker —
nothing is mounted, nothing is synced, nothing is cached on the device.

The point of the thing is writing one enormous file: a backup archive goes straight
from the producing app into the SFTP stream.

## Why Kotlin and not Rust or Go

You asked for Rust or Go if Android permits. It doesn't really, and forcing it would
make the app bigger rather than smaller:

* `DocumentsProvider` is a Java-API component. The system binds it by class name from
  the manifest and hands it `Cursor`, `ParcelFileDescriptor` and `CancellationSignal`
  objects. There is no NDK surface for any of it, so the provider itself has to be a
  JVM class no matter what.
* That leaves only the SFTP client to move into native code — via gomobile or JNI.
  You would pay for it with a Go runtime or a Rust `cdylib` per ABI (several MB each),
  a JNI boundary on every read and write, and two toolchains in the build.
* An SSH library in the JVM half costs one jar and keeps the whole app one language.

So: Kotlin, no AndroidX, no Compose, no dependency injection, no database. Four source
files, just under 1000 lines, plus [sshj](https://github.com/hierynomus/sshj) and
BouncyCastle for the protocol.

## How the big-file path works

`openDocument` has two modes, picked from the flags the client asks for.

**Write-only (`"w"`) — what a backup does.** The provider returns one end of a
`ParcelFileDescriptor.createReliablePipe()` and drains the other end straight into an
SFTP write stream on a worker thread:

* Nothing is staged in a temp file, so the file size is bounded by the remote
  filesystem, not by free space on the phone.
* Memory stays flat — one chunk sized to the server's maximum SFTP packet, plus up to
  32 write requests in flight so throughput isn't one round-trip per chunk.
* The pipe gives backpressure for free: when the network is the bottleneck the writing
  app blocks on `write()` instead of buffering.
* If the transfer fails the read end is closed *with an error*, which fails the client's
  `write`/`close` rather than letting it believe a truncated upload succeeded.
* A partial wake lock is held while bytes are moving, so a multi-hour upload survives the
  screen going off.

**Read or read/write.** These go through `StorageManager.openProxyFileDescriptor`, so
clients can seek. Sequential reads reuse one read-ahead stream; only an actual seek pays
for a new request chain. This path is slower than the pipe — it crosses a FUSE mount per
operation — which is why write-only opens are kept separate.

## Build

```sh
cd android-sftp-saf
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
```

Needs a JDK 17+ and an Android SDK with platform 35. CI (`.github/workflows/android.yml`)
builds the same APK on every push and uploads it as an artifact.

R8 is switched off for release builds on purpose: BouncyCastle resolves most of its
algorithms reflectively, so shrinking it needs keep rules that fail at runtime rather
than at build time. `app/proguard-rules.pro` has the rules if you want to trade that
risk for roughly half the APK size.

## Use

1. Open the app, fill in host, user, the remote directory to expose, and either a
   password or a private key file.
2. "Connect and add" dials the server and shows its host key fingerprint. Check it
   against `ssh-keyscan -p 22 your.host | ssh-keygen -lf -` run somewhere you trust,
   then confirm. The key is pinned; if it ever changes, connections fail instead of
   quietly trusting the new one.
3. The server now appears under "Browse" in the Files app and in any app's
   "Save to..." / "Open from..." picker.

Supported: browse, create, rename, move (within one server), delete (recursive),
read, write. Password and public-key auth (RSA, ECDSA, ed25519; OpenSSH and PEM key
files, with or without a passphrase).

## Things worth knowing

* **Credentials** are stored in app-private `SharedPreferences`, and a chosen key file is
  copied into app-private storage. That's the app sandbox plus whatever file-based
  encryption the device does — not a hardened keystore.
* **A completed `close()` isn't a completed upload.** A pipe write returns as soon as the
  bytes are in the pipe, so the last chunk may still be in flight when the writing app
  thinks it's done. Errors surface as a failed `write`/`close` when the client is still
  writing, but an app that closes early and immediately checks the file size may see a
  short read. This is inherent to streaming through SAF, not specific to this provider.
* **No random-access writes on the fast path.** A `"w"` open is a forward-only stream.
  Apps that seek while writing get the slower proxy-descriptor path instead.
* **One connection per server**, shared by all operations and reconnected on demand.
  Browsing while a large upload runs is fine; it just shares the link.
* **Moves between two configured servers** are refused — copy instead.
* Free space is not reported: it needs the `statvfs@openssh.com` extension, which sshj
  does not expose.
