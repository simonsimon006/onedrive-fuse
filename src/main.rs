use fuser::MountOption;
use fuser::{mount2, Filesystem};
use std::path::Path;

struct Folder {
    items: u8,
    content: String,
}

impl Filesystem for Folder {}

fn main() {
    let fs = Folder {
        items: 1,
        content: "Hello World!".to_string(),
    };
    let path = Path::new("/home/simon/Downloads/stuff");
    let result = mount2(fs, path, &[MountOption::RW]);
    let msg = match result {
        Ok(_) => "Yay!".to_string(),
        Err(e) => e.to_string(),
    };
    println!("{}", msg);
}
