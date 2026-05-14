use jni::{JavaVM, sys::jint};
use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::strings::JNIString;
use std::io::Read;
use std::os::fd::FromRawFd;
use std::fs::File;
use jni::objects::JByteArray;
use std::sync::mpsc::{channel, Receiver, Sender};
use std::sync::{Mutex, OnceLock};

static JVM: OnceLock<JavaVM> = OnceLock::new();
static BLE_SENDER: OnceLock<Mutex<Sender<Vec<u8>>>> = OnceLock::new();
static FILE_SENDER: OnceLock<Mutex<Sender<(String, File)>>> = OnceLock::new();
pub static FILE_RECEIVER: OnceLock<Mutex<Receiver<(String, File)>>> = OnceLock::new();
pub static BLE_RECEIVER: OnceLock<Mutex<Receiver<Vec<u8>>>> = OnceLock::new();

#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_com_nmaster23_rustdrop_android_MainActivity_initJniBridge<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
) {
    if let Ok(vm) = env.get_java_vm() { let _ = JVM.set(vm); }
    
    // Init File Channel
    let (tx_file, rx_file) = channel();
    let _ = FILE_SENDER.set(Mutex::new(tx_file));
    let _ = FILE_RECEIVER.set(Mutex::new(rx_file));
    
    // Init Bluetooth Channel
    let (tx_ble, rx_ble) = channel();
    let _ = BLE_SENDER.set(Mutex::new(tx_ble));
    let _ = BLE_RECEIVER.set(Mutex::new(rx_ble));
}

#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub fn open_picker() {
    if let Some(vm) = JVM.get() {
        let mut env = vm.attach_current_thread().unwrap();
        let class = env.find_class("com/nmaster23/rustdrop/android/MainActivity").unwrap();
        env.call_static_method(class, "FilePickerTrigger", "()V", &[]).unwrap();
    }
}

#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_nmaster23_rustdrop_android_MainActivity_FilePicker<'local>(
    mut env: JNIEnv<'local>,
    class: JClass<'local>,
    filename_j: JString<'local>,
    filepath_j: jint
    ) 
    {
    let filename = match env.get_string(&filename_j) {
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

#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_com_nmaster23_rustdrop_android_MainActivity_onBlueChunkReceived<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    chunk_j: JByteArray<'local>,
) {
    // Safely convert Java byte[] to Rust Vec<u8>
    if let Ok(chunk) = env.convert_byte_array(&chunk_j) {
        if let Some(sender_mutex) = BLE_SENDER.get() {
            let sender = sender_mutex.lock().unwrap();
            let _ = sender.send(chunk);
        }
    }
}

#[unsafe(no_mangle)] 
fn android_main(app: slint::android::AndroidApp) {
    slint::android::init(app).unwrap();
    let main_window = ..;
    main_window.run().unwrap();
}