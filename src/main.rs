mod one_fs;

use fuser::{mount2, MountOption};
use std::fs;
use std::path::Path;

fn main() {
    let dummy_content = fs::read(Path::new(
        "/home/simon/Projekte/onedrive-fuse/dummyfile.txt",
    ))
    .expect("Dummy file inaccessible.");

    let fs = one_fs::Folder {
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
