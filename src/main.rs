use fuser::MountOption;
use fuser::{mount2, FileAttr, FileType, Filesystem};

use std::fs;
use std::path::Path;
use std::time::{Duration, UNIX_EPOCH};

const TTL: Duration = Duration::from_secs(1);
const ENOENT: i32 = 2;
struct Folder {
    default_name: &'static str,
    content: Vec<u8>,
}

const HELLO_DIR_ATTR: FileAttr = FileAttr {
    ino: 1,
    size: 0,
    blocks: 0,
    atime: UNIX_EPOCH, // 1970-01-01 00:00:00
    mtime: UNIX_EPOCH,
    ctime: UNIX_EPOCH,
    crtime: UNIX_EPOCH,
    kind: FileType::Directory,
    perm: 0o755,
    nlink: 2,
    uid: 501,
    gid: 20,
    rdev: 0,
    flags: 0,
    blksize: 512,
};

const HELLO_TXT_ATTR: FileAttr = FileAttr {
    ino: 2,
    size: 13,
    blocks: 1,
    atime: UNIX_EPOCH, // 1970-01-01 00:00:00
    mtime: UNIX_EPOCH,
    ctime: UNIX_EPOCH,
    crtime: UNIX_EPOCH,
    kind: FileType::RegularFile,
    perm: 0o644,
    nlink: 1,
    uid: 501,
    gid: 20,
    rdev: 0,
    flags: 0,
    blksize: 512,
};

impl Filesystem for Folder {
    /*fn open(&mut self, _req: &fuser::Request<'_>, _ino: u64, _flags: i32, reply: fuser::ReplyOpen) {
    reply.opened(0, 0);
    }*/
    fn lookup(
        &mut self,
        _req: &fuser::Request<'_>,
        parent: u64,
        name: &std::ffi::OsStr,
        reply: fuser::ReplyEntry,
    ) {
        if parent != 1 {
            reply.error(1);
        } else {
            if name.to_str().expect("There should have been a name.") == self.default_name {
                reply.entry(&TTL, &HELLO_TXT_ATTR, 0);
            }
        }
    }
    fn getattr(&mut self, _req: &fuser::Request<'_>, ino: u64, reply: fuser::ReplyAttr) {
        match ino {
            1 => reply.attr(&TTL, &HELLO_DIR_ATTR),
            2 => reply.attr(&TTL, &HELLO_TXT_ATTR),
            _ => reply.error(ENOENT),
        }
    }
    fn read(
        &mut self,
        _req: &fuser::Request<'_>,
        ino: u64,
        fh: u64,
        offset: i64,
        size: u32,
        flags: i32,
        lock_owner: Option<u64>,
        reply: fuser::ReplyData,
    ) {
        match ino {
            2 => reply.data(&self.content[offset as usize..]),
            _ => reply.error(ENOENT),
        }
    }
    fn readdir(
        &mut self,
        _req: &fuser::Request<'_>,
        ino: u64,
        fh: u64,
        offset: i64,
        mut reply: fuser::ReplyDirectory,
    ) {
        if ino != 1 {
            return reply.error(ENOENT);
        }
        if offset == 0 {
            reply.add(1, 1, FileType::Directory, ".");
            reply.add(1, 2, FileType::Directory, "..");
            reply.add(2, 3, FileType::RegularFile, self.default_name);
        }
        reply.ok();
    }
}

fn main() {
    let dummy_content = fs::read(Path::new(
        "/home/simon/Projekte/onedrive-fuse/dummyfile.txt",
    ))
    .expect("Dummy file inaccessible.");

    let fs = Folder {
        default_name: "stuff.txt",
        content: dummy_content,
    };

    let path = Path::new("/home/simon/Downloads/stuff");

    let result = mount2(fs, path, &[MountOption::RO]);
    let msg = match result {
        Ok(_) => "Yay!".to_string(),
        Err(e) => e.to_string(),
    };

    println!("{}", msg);
}
