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
files, about 1100 lines, plus [sshj](https://github.com/hierynomus/sshj) and
BouncyCastle for the protocol.

## How the big-file path works

Every open — reads, writes and backups alike — is served through
`StorageManager.openProxyFileDescriptor`: the app gets a real file descriptor, and its
reads and writes arrive in the provider as calls it answers over SFTP.

* **Nothing is staged locally.** Bytes go straight from the writing app into the SFTP
  stream, so file size is bounded by the remote filesystem, not by space on the phone,
  and memory stays flat.
* **Writes are pipelined.** Up to 32 write requests (~1 MiB) are in flight at once, so
  throughput isn't one network round-trip per chunk. Small writes from the app are
  coalesced into full packets.
* **Failures reach the app.** A failed write surfaces as `EIO` on a later `write()`, and
  at the latest on `fsync()`, which waits for every outstanding write to be acknowledged.
  After one failure every later write and `fsync` on that descriptor fails too, so an
  upload that lost data can never be reported as complete.
* **Seeking works**, for reads and writes. Sequential reads stream with read-ahead; a
  seek just starts a new read-ahead window.
* A partial wake lock is held while bytes are moving, so a multi-hour upload survives the
  screen going off. It lapses a minute after the last read or write, so a descriptor an
  app forgets to close cannot keep the phone awake.

An earlier version streamed write-only opens through a pipe instead. Pipes can't be
fsync'd, so a failure in the last megabyte of an upload could never reach the app — the
reason for the switch.

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

1. Open the app, fill in host, user, the remote folder to expose (empty means your
   home folder; `~/backups` and relative paths work too), and either a password or a
   private key file.
2. "Connect and add" dials the server and shows its host key fingerprint. Check it
   against `ssh-keyscan -p 22 your.host | ssh-keygen -lf -` run somewhere you trust,
   then confirm. The key is pinned; if it ever changes, connections fail instead of
   quietly trusting the new one.
3. The server now appears under "Browse" in the Files app and in any app's
   "Save to..." / "Open from..." picker.

Supported: browse, create, rename, move (within one server), delete (recursive),
read, write, seek. Password auth (including servers that only offer
keyboard-interactive, like FreeBSD and TrueNAS CORE) and public-key auth (RSA, ECDSA,
ed25519; OpenSSH and PEM key files, with or without a passphrase). The exposed folder
itself can't be deleted, renamed or moved from a file manager.

## Things worth knowing

* **Credentials** are stored in app-private `SharedPreferences`, and a chosen key file is
  copied into app-private storage. That's the app sandbox plus whatever file-based
  encryption the device does — not a hardened keystore.
* **`close()` can't report errors — `fsync()` can.** Android's proxy descriptors have no
  flush hook, so if the last ~1 MiB of writes fails *after* an app closes without
  fsyncing, the app can't be told (same as NFS). Apps that fsync before closing get
  every error. Some cloud-backed SFTP gateways only commit on close; their failures
  can't be surfaced either.
* **`"w"` truncates.** Since Android 10 plain `"w"` doesn't formally ask for truncation
  (only `"wt"` does), but a write-only open can only be a rewrite, and keeping the old
  tail would corrupt a shorter replacement backup. `"rw"` keeps existing content;
  `"rwt"` truncates.
* **One connection per server**, shared by all operations and reconnected on demand.
  Browsing while a large upload runs is fine; it just shares the link.
* **Moves between two configured servers** are refused — copy instead.
* Free space is not reported: it needs the `statvfs@openssh.com` extension, which sshj
  does not expose.
