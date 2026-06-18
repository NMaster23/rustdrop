#[cfg(not(target_os = "android"))]
use crate::{AppWindow, BlueDevice};
#[cfg(target_os = "android")]
use crate::RustDropUiCallback;

use async_std::stream::StreamExt;
use bluest::*;
use std::rc::Rc;
#[cfg(not(target_os = "android"))]
use rfd::FileDialog;
use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use async_std::future::timeout;
use std::time::Duration;
#[cfg(not(target_os = "android"))]
use ble_peripheral_rust::{
    gatt::{
        characteristic::Characteristic,
        peripheral_event::{
            PeripheralEvent, RequestResponse, WriteRequestResponse,
        },
        service::Service,
    },
    Peripheral, PeripheralImpl,
};
#[cfg(not(target_os = "android"))]
use tokio::sync::mpsc::channel;

#[cfg(not(target_os = "android"))]
struct BlueData {
    identifier: String,
    signal_strength: String,
    service_uuid: Vec<bluest::Uuid>,
}

const TARGET_CHAR: Uuid = Uuid::from_u128(0x12345678_1234_5678_1234_56789abcdef1);
const TARGET_SERVICE: Uuid = Uuid::from_u128(0x12345678_1234_5678_1234_56789abcdef0);

#[cfg(not(target_os = "android"))]
use slint::{SharedString, Model};

#[cfg(not(target_os = "android"))]
async fn send_file_blue(device: &Device, file_path: &str) {
    let mut service_char = None;
    let services = device.discover_services().await.unwrap();
    for service in services {
        let characteristics = service.discover_characteristics().await.unwrap();
        println!("Service: {:?}", service);
        for characteristic in characteristics {
            println!("Characteristics: {:?}", characteristic);
            if service.uuid() == TARGET_SERVICE && characteristic.uuid() == TARGET_CHAR {
                service_char = Some(characteristic);
                break;
            }
        }
    }
    let file_name_str = std::path::Path::new(file_path).file_name().and_then(|name| name.to_str()).unwrap_or("unknown_file");
    let file_name = file_name_str.as_bytes();
    let file_bytes = std::fs::read(file_path).expect("Failed to read file");
    let mut to_send = Vec::new();
    to_send.extend_from_slice(&file_size); 
    to_send.push(file_name.len() as u8);
    to_send.extend_from_slice(file_name);
    to_send.extend_from_slice(&file_bytes);
    if let Some(write_char) = service_char {
        for chunk in to_send.chunks(20) {
            if let Err(e) = write_char.write(chunk).await {
                println!("Error Sending Chunk: {}", e);
                break;
            }
            async_std::task::sleep(Duration::from_millis(10)).await;
        }
        println!("file sent");
    }
}

#[cfg(not(target_os = "android"))]
pub(crate) async fn receive_file_blue(ui_handle: slint::Weak<AppWindow>, file_accepted: Arc<Mutex<bool>>) {
    let (sender_tx, mut receiver_rx) = channel::<PeripheralEvent>(256);
    let mut peripheral = Peripheral::new(sender_tx).await.unwrap();
    while !peripheral.is_powered().await.unwrap() {}
    peripheral.add_service(
        &Service {
            uuid: TARGET_SERVICE,
            primary: true,
            characteristics: vec![
                Characteristic {
                    uuid: TARGET_CHAR,
                    ..Default::default()
                }
            ],
        }
    ).await;
    peripheral.start_advertising("RustDrop", &[TARGET_SERVICE]).await;
    let mut received_data = Vec::new();
    let mut is_receiving = false;
    loop {
        match receiver_rx.recv().await {
            Some(event) => {
                match event {
                    PeripheralEvent::WriteRequest { request: _, value, offset: _, responder } => {
                        if !is_receiving {
                            *file_accepted.lock().unwrap() = false;
                            let _ = ui_handle.upgrade_in_event_loop(|ui| ui.set_receiving_file(true));
                            is_receiving = true;
                        }
                        received_data.extend_from_slice(&value);
                        responder.send(WriteRequestResponse { response: RequestResponse::Success });
                    }
                    _ => {}
                }
            },
            None => break,
            Err(_) => {
                if received_data.len() > 1 {
                    let name_len = received_data[0] as usize;
                    if received_data.len() > name_len {
                        let mut filename = String::from_utf8_lossy(&received_data[1..name_len + 1]).to_string();
                        filename = filename.chars().filter(|c| c.is_alphanumeric() || *c == '.' || *c == '-' || *c == '_').collect();
                        if filename.is_empty() {
                            filename = "RustDrop_Received_File".to_string();
                        }
                        let file_data = &received_data[name_len + 1..];
                        let mut save_path = dirs::download_dir().unwrap_or_else(|| std::path::PathBuf::from("."));
                        save_path.push(&filename);
                        println!("Received file {} with {} bytes. Saving to {:?}", filename, file_data.len(), save_path);
                        
                        while !*file_accepted.lock().unwrap() {
                            async_std::task::sleep(std::time::Duration::from_millis(100)).await;
                        }
                        
                        if let Err(e) = async_std::fs::write(&save_path, file_data).await {
                            println!("Error saving file: {}", e);
                        } else {
                            println!("File saved successfully to {:?}", save_path);
                        }
                        let _ = ui_handle.upgrade_in_event_loop(|ui| ui.set_receiving_file(false));
                    }
                }
                is_receiving = false;
                received_data.clear();   
            }
        }
    }
}

#[cfg(not(target_os = "android"))]
pub(crate) async fn bluetooth(ui_handle: slint::Weak<AppWindow>) {
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
            let active_session_clone = Arc::clone(&active_session);
            *is_scanning_stop.lock().unwrap() = false;
            async_std::task::spawn(async move {
                let id_str = identifier.to_string();
                let device = identifier_name.lock().unwrap().get(&id_str).cloned().unwrap();
                let device_file = device.clone();
                adapter_connect.connect_device(&device_file).await.unwrap();
                println!("Connected to {}", identifier.to_string());
                *active_session_clone.lock().unwrap() = Some(device);
                let file = FileDialog::new()
                    .set_directory("/")
                    .pick_file();
                if let Some(path) = file {
                    let path_str = path.to_string_lossy().into_owned();
                    println!("{}", path_str);
                    send_file_blue(&device_file, &path_str).await;
                }
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
                    *is_scanning_start.lock().unwrap() = true;
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
            if !discovered_device.adv_data.services.contains(&TARGET_SERVICE) {
                continue;
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