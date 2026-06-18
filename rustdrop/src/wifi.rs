#[cfg(not(target_os = "android"))]
use crate::{AppWindow, WifiDevice};

use mdns_sd::{ServiceDaemon, ServiceInfo, ServiceEvent};
use local_ip_address::local_ip;
use std::net::{TcpListener, TcpStream};
use std::fs::File;
use std::io::{self, Read, Write};
use std::rc::Rc;
#[cfg(not(target_os = "android"))]
use rfd::FileDialog;
use chunked_transfer::{Encoder, Decoder};

#[cfg(not(target_os = "android"))]
use slint::{SharedString, Model};

fn send_file_wifi(ip: String, port: u32, file_path: &str) {
    let addr = format!("{}:{}", ip, port);
    if let Ok(mut stream) = TcpStream::connect(&addr) {
        println!("Connected to the server!");
        if let Ok(mut file) = File::open(file_path) {
            let decoded = std::path::Path::new(file_path).file_name().and_then(|name| name.to_str()).unwrap_or("unknown_file").as_bytes();
            let mut encoded: Vec<u8> = vec![];
            {
                let mut encoder = Encoder::with_chunks_size(&mut encoded, 8192);
                let _ = encoder.write_all(decoded);
            }
            stream.write_all(&[encoded.len() as u8]).unwrap();
            stream.write_all(&encoded).unwrap();
            match io::copy(&mut file, &mut stream) {
                Ok(bytes) => {
                    println!("Sent {} bytes successfully", bytes); 
                    let _ = stream.shutdown(std::net::Shutdown::Both);
                }
                Err(e) => println!("Failed to send file: {}", e),
            }
        } else {
            println!("Could not open file: {}", file_path);
        };
    } else {
        println!("Couldn't connect to server at {}", &addr);
    }
}

async fn receive_file_wifi(ui_handle: slint::Weak<AppWindow>, file_accepted: std::sync::Arc<std::sync::Mutex<bool>>) {
    let listener = TcpListener::bind("0.0.0.0:5200").unwrap();
    loop {
        match listener.accept() {
            Ok((mut socket, addr)) => {
                *file_accepted.lock().unwrap() = false;
                let _ = ui_handle.upgrade_in_event_loop(|ui| ui.set_receiving_file(true));
                let mut len_buf = [0u8; 1];
                let _ = socket.read_exact(&mut len_buf);
                let len = len_buf[0] as usize;
                let mut filename_buf = vec![0u8; len];
                let _ = socket.read_exact(&mut filename_buf);
                let mut filename = String::new();
                let encoded = vec![];
                let mut decoded = String::new();
                let mut filename_u8 = &filename_buf as &[u8];
                let mut filename_decoder = Decoder::new(&mut filename_u8);
                let _ = filename_decoder.read_to_string(&mut filename);
                
                while !*file_accepted.lock().unwrap() {
                    async_std::task::sleep(std::time::Duration::from_millis(100)).await;
                }
                
                let mut save_path = dirs::download_dir().unwrap_or_else(|| std::path::PathBuf::from("."));
                save_path.push(&filename);
                let mut file = File::create(&save_path).unwrap();
                let mut decoder = Decoder::new(&encoded as &[u8]);
                let _ = decoder.read_to_string(&mut decoded);
                match io::copy(&mut socket, &mut file) {
                    Ok(bytes) => {
                        println!("Received {} bytes and saved to {:?}", bytes, save_path);
                        let _ = ui_handle.upgrade_in_event_loop(|ui| ui.set_receiving_file(false));
                    },
                    Err(e) => println!("Error during reception: {}", e),
                }
            }
            Err(e) => println!("Connection error: {e:?}"),
        }
    }
}

#[cfg(not(target_os = "android"))]
pub(crate) async fn wifi(mdns: ServiceDaemon, ui_handle: slint::Weak<AppWindow>, file_accepted: std::sync::Arc<std::sync::Mutex<bool>>) {
    let service_type = "_rustdrop._tcp.local.";
    let instance_name = "rustdrop";
    let host_name = "rustdrop.local.";
    let port = 5200;
    let receiver = mdns.browse(service_type).expect("Failed to browse");
    let ip = local_ip().unwrap().to_string();
    let rustdrop_service = ServiceInfo::new(
        service_type,
        instance_name,
        host_name,
        ip,
        port,
        None,
    ).unwrap();
    mdns.register(rustdrop_service).expect("Failed to register our service");
    let ui_handle_recv = ui_handle.clone();
    async_std::task::spawn(async move {
        receive_file_wifi(ui_handle_recv, file_accepted).await;
    });
    let ui_handle_clone = ui_handle.clone();
    async_std::task::spawn(async move {
        while let Ok(event) = receiver.recv() {
            if let ServiceEvent::ServiceResolved(resolved) = event {
                println!("Resolved a new service: {}", resolved.fullname);
                let name = resolved.get_hostname().to_string();
                let ip = resolved.get_addresses().into_iter().map(|a| a.to_string()).next().unwrap_or_default();
                ui_handle_clone.upgrade_in_event_loop(move |ui| {
                    let mut devices: Vec<WifiDevice> = ui.get_wifi_devices().iter().collect();
                    devices.push(WifiDevice {
                        name: name.into(),
                        ip: ip.into()
                    });
                    ui.set_wifi_devices(slint::ModelRc::from(Rc::new(slint::VecModel::from(devices))));
                }).unwrap();
            }
        }
    });
    let ui_handle_request = ui_handle.clone();
    ui_handle.upgrade_in_event_loop(move |ui| {
        ui.on_send_select_device_wifi(move |device_ip: SharedString| {
            let file = FileDialog::new()
                .set_directory("/")
                .pick_file();
            if let Some(path) = file {
                let path_str = path.to_string_lossy().into_owned();
                send_file_wifi(device_ip.to_string(), 5200, &path_str);
            }
        });
    });
}