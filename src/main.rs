use mdns_sd::{ServiceDaemon, ServiceInfo, ServiceEvent};
use async_std::stream::StreamExt;
use mdns_sd::Error;
use bluest::*;
use local_ip_address::local_ip;
use slint::SharedString;
use std::net::{TcpListener, TcpStream};
use std::fs::File;
use std::io::{self, Write};
use slint::Model;
use std::rc::Rc;
use rfd::FileDialog;
use std::io::Read;
use chunked_transfer::{Encoder, Decoder};
use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use async_std::future::timeout;
use std::time::Duration;

slint::include_modules!();

struct BlueData {
    identifier: String,
    signal_strength: String,
    service_uuid: Vec<bluest::Uuid>,
}

fn send_file_wifi(ip: String, port: u32, file_path: &str) {
    let addr = format!("{}:{}", ip, port);
    if let Ok(mut stream) = TcpStream::connect(&addr) {
        println!("Connected to the server!");
        if let Ok(mut file) = File::open(file_path) {
            let decoded = std::path::Path::new(file_path).file_name().unwrap().to_str().unwrap().as_bytes();
            let mut encoded: Vec<u8> = vec![];
            {
                let mut encoder = Encoder::with_chunks_size(&mut encoded, 5);
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

fn receive_file_wifi() {
    let listener = TcpListener::bind("0.0.0.0:5200").unwrap();
    loop {
        match listener.accept() {
            Ok((mut socket, addr)) => {
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
                let mut file = File::create(filename).unwrap();
                let mut decoder = Decoder::new(&encoded as &[u8]);
                let _ = decoder.read_to_string(&mut decoded);
                match io::copy(&mut socket, &mut file) {
                    Ok(bytes) => println!("Received {} bytes and saved to ", bytes),
                    Err(e) => println!("Error during reception: {}", e),
                }
            }
            Err(e) => println!("Connection error: {e:?}"),
        }
    }
}

async fn bluetooth(ui_handle: slint::Weak<AppWindow>) {
    let adapter = Arc::new(Adapter::default().await.ok_or("Bluetooth adapter not found").unwrap());
    let adapter_ui = Arc::clone(&adapter);
    let adapter_disconnect = Arc::clone(&adapter);
    let adapter_timeout = adapter.wait_available();
    if let Err(_) = timeout(Duration::from_secs(1), adapter_timeout).await {
        println!("Please check whether your device supports Bluetooth, or if your Bluetooth is turned off.");
        return;
    }
    println!("starting scan");
    let mut scan = adapter.scan(&[]).await.unwrap();
    println!("scan started");
    let is_scanning = Arc::new(Mutex::new(true));
    let is_scanning_stop = Arc::clone(&is_scanning);
    let is_scanning_start = Arc::clone(&is_scanning);
    let ui_handle_clone = ui_handle.clone();
    let identifier_name = Arc::new(Mutex::new(HashMap::<String, Device>::new()));
    let identifier_name_map = Arc::clone(&identifier_name);
    let ui_handle_request = ui_handle.clone();
    let active_session = Arc::new(Mutex::new(None));
    let disconnect_session = Arc::clone(&active_session);
    let _ = ui_handle_request.upgrade_in_event_loop(move |ui| {
        let identifier_name = Arc::clone(&identifier_name);
        ui.on_send_select_device_blue(move |identifier: SharedString| {
            let adapter_connect = Arc::clone(&adapter_ui);
            let identifier_name = Arc::clone(&identifier_name);
            //let file = FileDialog::new()
            //    .set_directory("/")
            //    .pick_file();
            //let path_str = file.map(|p| p.to_string_lossy().into_owned()).unwrap_or_default();
            let active_session_clone = Arc::clone(&active_session);
            *is_scanning_stop.lock().unwrap() = false;
            async_std::task::spawn(async move {
                let id_str = identifier.to_string();
                let device = identifier_name.lock().unwrap().get(&id_str).cloned().unwrap();
                adapter_connect.connect_device(&device).await.unwrap();
                println!("Connected to {}", identifier.to_string());
                *active_session_clone.lock().unwrap() = Some(device);
            });
        });
        ui.on_disconnect(move || {
            if let Some(session) = disconnect_session.lock().unwrap().take() {
                let device = session.clone();
                let adapter_async = Arc::clone(&adapter_disconnect);
                let is_scanning_start = Arc::clone(&is_scanning_start);
                async_std::task::spawn(async move {
                    adapter_async.disconnect_device(&device).await.unwrap();
                    println!("Disconnected");
                    *is_scanning_start.lock().unwrap() == true;
                });
            }
        });
    });
    if *is_scanning.lock().unwrap() {
        println!("Scan started");
        while let Some(discovered_device) = scan.next().await {
            if !*is_scanning.lock().unwrap() {
                println!("Scan stopped");
                break;
            }
            let blue_data = BlueData {
                identifier: discovered_device.device.name().as_deref().unwrap_or("(unknown)").to_string(),
                signal_strength: discovered_device.rssi.map(|x| format!(" ({}dBm)", x)).unwrap_or_default(),
                service_uuid: discovered_device.adv_data.services
            };
            let hashmap_identifier = discovered_device.device.clone();
            let debug = discovered_device.device.name().as_deref().unwrap_or("(unknown)").to_string();
            let mut hashmap = identifier_name_map.lock().unwrap();
            hashmap.insert(blue_data.identifier.clone(), hashmap_identifier);
            ui_handle_clone.upgrade_in_event_loop(move |ui| {
                let mut devices: Vec<BlueDevice> = ui.get_blue_devices().iter().collect();
                let identifier = blue_data.identifier.into();
                if let Some(pos) = devices.iter().position(|d| d.identifier == identifier) {
                    devices[pos] = BlueDevice { identifier }
                } else {
                    devices.push(BlueDevice { identifier })
                }
                ui.set_blue_devices(slint::ModelRc::from(Rc::new(slint::VecModel::from(devices))));
            }).unwrap();
        }
    }
}

async fn wifi(mdns: ServiceDaemon, ui_handle: slint::Weak<AppWindow>) {
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
    async_std::task::spawn(async move {
        receive_file_wifi();
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
            let path_str = file.map(|p| p.to_string_lossy().into_owned()).unwrap_or_default();
            send_file_wifi(device_ip.to_string(), 5200, &path_str);
        });
    });
}

#[async_std::main]
async fn main() -> Result<(), Error> {
    let ui = AppWindow::new().unwrap();
    let mdns = ServiceDaemon::new().expect("Failed to create daemon");
    let ui_clone = ui.as_weak();
    ui.on_send_mode(move |blue_or_wifi: bool| {
        if blue_or_wifi {
            let blue_ui = ui_clone.clone();
            async_std::task::spawn(bluetooth(blue_ui));
        }
        if !blue_or_wifi {
            let mdns_clone = mdns.clone();
            let wifi_ui = ui_clone.clone();
            async_std::task::spawn(wifi(mdns_clone, wifi_ui));
        }
    });
    ui.run().expect("UI Initialization Error");
    Ok(())
}