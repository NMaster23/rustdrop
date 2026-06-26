#[cfg(not(target_os = "android"))]
use crate::{AppWindow, WifiDevice};

use mdns_sd::{ServiceDaemon, ServiceInfo, ServiceEvent};
use local_ip_address::local_ip;
use std::net::TcpStream;
use std::fs::File;
use std::io::{self, Read, Write};
use std::rc::Rc;
use async_std::io::ReadExt;
#[cfg(not(target_os = "android"))]
use rfd::FileDialog;
use chunked_transfer::{Encoder, Decoder};

#[cfg(not(target_os = "android"))]
use slint::{SharedString, Model};

fn send_file_wifi(ui_handle: slint::Weak<AppWindow>, ip: String, port: u32, file_path: &str) {
    let addr = if ip.contains(':') {
        format!("[{}]:{}", ip, port)
    } else {
        format!("{}:{}", ip, port)
    };
    if let Ok(mut stream) = TcpStream::connect(&addr) {
        let _ = ui_handle.upgrade_in_event_loop(|ui| ui.set_transfer_status("Connected to the server!".into()));
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
                    let status = format!("Sent {} bytes successfully", bytes);
                    let _ = ui_handle.upgrade_in_event_loop(move |ui| ui.set_transfer_status(status.clone().into()));
                    let _ = stream.shutdown(std::net::Shutdown::Both);
                }
                Err(e) => {
                    let error_msg = format!("Failed to send file: {}", e);
                    let _ = ui_handle.upgrade_in_event_loop(move |ui| ui.set_transfer_status(error_msg.into()));
                }
            }
        } else {
            let error_msg = format!("Could not open file: {}", file_path);
            let _ = ui_handle.upgrade_in_event_loop(move |ui| ui.set_transfer_status(error_msg.into()));
        };
    } else {
        let error_msg = format!("Couldn't connect to server at {}", &addr);
        let _ = ui_handle.upgrade_in_event_loop(move |ui| ui.set_transfer_status(error_msg.into()));
    }
}

async fn receive_file_wifi(ui_handle: slint::Weak<AppWindow>, file_accepted: std::sync::Arc<std::sync::Mutex<Option<bool>>>) {
    let listener = async_std::net::TcpListener::bind("0.0.0.0:5200").await.unwrap();
    loop {
        match listener.accept().await {
            Ok((mut socket, _addr)) => {
                *file_accepted.lock().unwrap() = None;
                let _ = ui_handle.upgrade_in_event_loop(|ui| {
                    ui.set_receiving_file(true);
                    ui.set_transfer_progress(0.05);
                });
                let mut len_buf = [0u8; 1];
                let _ = socket.read_exact(&mut len_buf).await;
                let len = len_buf[0] as usize;
                let mut filename_buf = vec![0u8; len];
                let _ = socket.read_exact(&mut filename_buf).await;
                let mut filename = String::new();
                let mut filename_u8 = &filename_buf as &[u8];
                let mut filename_decoder = Decoder::new(&mut filename_u8);
                let _ = filename_decoder.read_to_string(&mut filename);
                
                while file_accepted.lock().unwrap().is_none() {
                    async_std::task::sleep(std::time::Duration::from_millis(100)).await;
                }
                
                if let Some(false) = *file_accepted.lock().unwrap() {
                    let _ = ui_handle.upgrade_in_event_loop(move |ui| {
                        ui.set_transfer_message("File transfer rejected".into());
                        ui.set_show_transfer_message(true);
                    });
                    continue;
                }
                
                let mut save_path = dirs::download_dir().unwrap_or_else(|| std::path::PathBuf::from("."));
                save_path.push(&filename);
                let mut file = async_std::fs::File::create(&save_path).await.unwrap();
                let start_time = std::time::Instant::now();
                let _ = ui_handle.upgrade_in_event_loop(|ui| ui.set_transfer_progress(0.5));
                
                let result = async_std::io::copy(&mut socket, &mut file).await;
                
                let _ = ui_handle.upgrade_in_event_loop(|ui| ui.set_transfer_progress(1.0));
                
                let elapsed = start_time.elapsed().as_secs_f32();
                if elapsed < 1.0 {
                    async_std::task::sleep(std::time::Duration::from_secs_f32(1.0 - elapsed)).await;
                }
                
                let message = match result {
                    Ok(bytes) => {
                        format!("Received {} bytes and saved to {:?}", bytes, save_path)
                    },
                    Err(e) => {
                        format!("Error during reception: {}", e)
                    }
                };
                
                let _ = ui_handle.upgrade_in_event_loop(move |ui| {
                    ui.set_transfer_message(message.clone().into());
                    ui.set_show_transfer_message(true);
                });
                
                let _ = ui_handle.upgrade_in_event_loop(|ui| {
                    ui.set_receiving_file(false);
                    ui.set_transfer_progress(0.0);
                    ui.set_show_transfer_message(false);
                });
            }
            Err(e) => {
                let error_msg = format!("Connection error: {e:?}");
                let _ = ui_handle.upgrade_in_event_loop(move |ui| ui.set_transfer_status(error_msg.into()));
            }
        }
    }
}

#[cfg(not(target_os = "android"))]
pub(crate) async fn wifi(mdns: ServiceDaemon, ui_handle: slint::Weak<AppWindow>, file_accepted: std::sync::Arc<std::sync::Mutex<Option<bool>>>) {
    let service_type = "_rustdrop._tcp.local.";
    let instance_name = format!("rustdrop.{}", whoami::hostname().unwrap_or_else(|_| "<unknown>".to_string()));
    let host_name = format!("rustdrop.{}.local.", whoami::hostname().unwrap_or_else(|_| "<unknown>".to_string()));
    let port = 5200;
    let receiver = mdns.browse(service_type).expect("Failed to browse");
    let ip = local_ip().unwrap().to_string();
    let rustdrop_service = ServiceInfo::new(
        service_type,
        &instance_name.to_string(),
        host_name.as_str(),
        ip,
        port,
        None,
    ).unwrap();
    let host_name_clone = host_name.clone();
    let _ = ui_handle.upgrade_in_event_loop(move |ui| ui.set_transfer_status(format!("Hostname: {}", host_name_clone).into()));
    mdns.register(rustdrop_service).expect("Failed to register our service");
    let _ = ui_handle.upgrade_in_event_loop(move |ui| ui.set_transfer_status(format!("Service registered: {}", host_name).into()));
    let ui_handle_recv = ui_handle.clone();
    async_std::task::spawn(async move {
        receive_file_wifi(ui_handle_recv, file_accepted).await;
    });
    let ui_handle_clone = ui_handle.clone();
    async_std::task::spawn(async move {
        while let Ok(event) = receiver.recv() {
            if let ServiceEvent::ServiceResolved(resolved) = event {
                let status = format!("Resolved a new service: {}", resolved.fullname);
                let _ = ui_handle_clone.upgrade_in_event_loop(move |ui| ui.set_transfer_status(status.clone().into()));
                let name = resolved.get_hostname().to_string();
                let ip = resolved.get_addresses().iter().find(|a| a.is_ipv4()).or_else(|| resolved.get_addresses().iter().next()).map(|a| a.to_string()).unwrap_or_default();
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
                send_file_wifi(ui_handle_request.clone(), device_ip.to_string(), 5200, &path_str);
            }
        });
    });
}