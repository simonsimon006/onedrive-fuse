*THIS IS EXPERIMENTAL*
# Goal
I want to mount onedrive through fuse and, later on, implement nice caching. The idea is to access onedrive without having to sync everything to your local device.

# State
- Not working

# android-sftp-saf
A separate, working thing that lives in this repo: an Android app that exposes an SFTP
server through the system Storage Access Framework, so it appears in the Files app and
in every file picker without syncing anything to the device. Built for streaming single
huge files (backups) straight into SFTP. See [android-sftp-saf/README.md](android-sftp-saf/README.md).
