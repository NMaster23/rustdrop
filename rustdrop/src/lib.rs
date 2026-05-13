#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
use jni::sys::jint;
use jni::Env;
use jni::objects::{JClass, JString};
use jni::strings::JNIString;
use std::io::Read;
use std::os::fd::FromRawFD;
use std::fs::File;

pub extern "system" fn Java_MainActivity_FilePicker<'caller>(filename_j: JString<'local>, filepath_j: jint, mut unowned_env: EnvUnowned<'caller>, class: JClass<'caller>, input: JString<'caller>) -> JString<'caller> {    
    let filename = match unowned_env.get_string(&filename_j) {
        Ok(name) => name.into(),
        Err(e) => {
            eprintln!("{:?}", e);
            return;
        }
    };
    println!("Received File {} at {}", filename, filepath_j);
    let mut filepath = unsafe { File::from_raw_fd(filepath_j) };
    let mut buffer = [0u8; 65536];
    let mut read_bytes_total = 0;
    loop {
        match filepath.read(&mut buffer) {
            Ok(0) => break,
            Ok(bytes_read) => {
                read_bytes_total += bytes_read;
            }
            Err(e) => {
                eprintln!("{:?}", e);
                return;
            }
        }
    }
    println!("Read {} bytes", read_bytes_total);
}

fn android_main(app: slint::android::AndroidApp) {
    slint::android::init(app).unwrap();
    let main_window = ..;
    main_window.run().unwrap();
}