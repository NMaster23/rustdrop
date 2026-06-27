#[cfg(not(target_os = "android"))]
use crate::{AppWindow, BlueDevice};
#[cfg(target_os = "android")]
use crate::RustDropUiCallback;

use futures_util::stream::StreamExt;
use bluest::*;
use std::rc::Rc;
#[cfg(not(target_os = "android"))]
use rfd::FileDialog;
use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use tokio::time::timeout;
use std::time::Duration;
#[cfg(not(target_os = "android"))]
use ble_peripheral_rust::{
    gatt::{
        characteristic::Characteristic,
        properties::CharacteristicProperty,
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

const TARGET_CHAR: Uuid = Uuid::from_u128(0x00002AC5_0000_1000_8000_00805F9B34FB);
const TARGET_SERVICE: Uuid = Uuid::from_u128(0x00001825_0000_1000_8000_00805F9B34FB);

#[cfg(not(target_os = "android"))]
use slint::{SharedString, Model};

#[cfg(not(target_os = "android"))]
async fn send_file_blue(adapter: &Adapter, device: &Device, file_path: &str, ui_handle: slint::Weak<AppWindow>) -> bool {
    let mut service_char = None;
    for attempt in 1..=5 {
        let msg = format!("Service discovery attempt {}/5...", attempt);
        let _ = ui_handle.upgrade_in_event_loop(move |ui| ui.set_transfer_status(msg.clone().into()));
        let services = match timeout(Duration::from_secs(20), device.discover_services()).await {
            Ok(Ok(s)) if !s.is_empty() => {
                let msg = format!("Found {} services", s.len());
                let _ = ui_handle.upgrade_in_event_loop(move |ui| ui.set_transfer_status(msg.clone().into()));
                for service in &s {
                    let uuid_msg = format!("Discovered service UUID: {}", service.uuid());
                    let _ = ui_handle.upgrade_in_event_loop(move |ui| ui.set_transfer_status(uuid_msg.clone().into()));
                }
                s
            }
            Ok(Ok(_)) => {
                let _ = ui_handle.upgrade_in_event_loop(|ui| ui.set_transfer_status("No services found (empty cache). Retrying...".into()));
                let _ = adapter.disconnect_device(device).await;
                tokio::time::sleep(Duration::from_secs(1)).await;
                let _ = adapter.connect_device(device).await;
                continue;
            }
            Ok(Err(e)) => {
                let error_msg = format!("Service discovery error: {}. Retrying...", e);
                let _ = ui_handle.upgrade_in_event_loop(move |ui| ui.set_transfer_status(error_msg.clone().into()));
                let _ = adapter.disconnect_device(device).await;
                tokio::time::sleep(Duration::from_secs(1)).await;
                let _ = adapter.connect_device(device).await;
                continue;
            }
            Err(_) => {
                let _ = ui_handle.upgrade_in_event_loop(|ui| ui.set_transfer_status("Service discovery timed out. Retrying...".into()));
                if attempt >= 3 {
                    let _ = adapter.disconnect_device(device).await;
                    tokio::time::sleep(Duration::from_secs(1)).await;
                    let _ = adapter.connect_device(device).await;
                }
                continue;
            }
        };
        for service in services {
            if service.uuid() == TARGET_SERVICE {
                let _ = ui_handle.upgrade_in_event_loop(|ui| ui.set_transfer_status("Found TARGET_SERVICE!".into()));
                let characteristics = match service.discover_characteristics().await {
                    Ok(c) => c,
                    Err(e) => {
                        let err_msg = format!("Characteristic discovery failed: {}", e);
                        let _ = ui_handle.upgrade_in_event_loop(move |ui| ui.set_transfer_status(err_msg.clone().into()));
                        continue;
                    }
                };
                for characteristic in characteristics {
                    if characteristic.uuid() == TARGET_CHAR {
                        let _ = ui_handle.upgrade_in_event_loop(|ui| ui.set_transfer_status("Found TARGET_CHAR!".into()));
                        service_char = Some(characteristic);
                        break;
                    }
                }
            }
        }
        if service_char.is_some() {
            break;
        }
        let _ = ui_handle.upgrade_in_event_loop(|ui| ui.set_transfer_status("TARGET_CHAR not found in services. Retrying...".into()));
        let _ = adapter.disconnect_device(device).await;
        tokio::time::sleep(Duration::from_secs(2)).await;
        let _ = adapter.connect_device(device).await;
    }
    let file_name_str = std::path::Path::new(file_path).file_name().and_then(|name| name.to_str()).unwrap_or("unknown_file");
    let file_name = file_name_str.as_bytes();
    let file_bytes = std::fs::read(file_path).expect("Failed to read file");
    let file_size = file_bytes.len() as u64; 
    let mut to_send = Vec::new();
    to_send.extend_from_slice(&file_size.to_le_bytes());
    to_send.push(file_name.len() as u8);
    to_send.extend_from_slice(file_name);
    to_send.extend_from_slice(&file_bytes);
    if let Some(write_char) = service_char {
        for chunk in to_send.chunks(20) {
            if let Err(e) = write_char.write(chunk).await {
                let err_msg = format!("Error Sending Chunk: {}", e);
                let _ = ui_handle.upgrade_in_event_loop(move |ui| ui.set_transfer_status(err_msg.clone().into()));
                return false;
            }
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
        let _ = ui_handle.upgrade_in_event_loop(|ui| ui.set_transfer_status("File sent successfully".into()));
        true
    } else {
        let _ = ui_handle.upgrade_in_event_loop(|ui| ui.set_transfer_status("TARGET_CHAR not found on device".into()));
        false
    }
}

#[cfg(not(target_os = "android"))]
pub(crate) async fn receive_file_blue(ui_handle: slint::Weak<AppWindow>, file_accepted: Arc<Mutex<Option<bool>>>) {
    let (sender_tx, mut receiver_rx) = channel::<PeripheralEvent>(256);
    let mut peripheral = Peripheral::new(sender_tx).await.unwrap();
    while !peripheral.is_powered().await.unwrap() {}
    let _ = peripheral.add_service(
        &Service {
            uuid: TARGET_SERVICE,
            primary: true,
            characteristics: vec![
                Characteristic {
                    uuid: TARGET_CHAR,
                    properties: vec![CharacteristicProperty::Write, CharacteristicProperty::WriteWithoutResponse],
                    ..Default::default()
                }
            ],
        }
    ).await;
    let host_name = whoami::hostname().unwrap_or_else(|_| "RustDrop".to_string());
    let _ = peripheral.start_advertising(&host_name, &[TARGET_SERVICE]).await;
    let mut received_data = Vec::new();
    let mut is_receiving = false;
    loop {
        match receiver_rx.recv().await {
            Some(event) => {
                match event {
                    PeripheralEvent::WriteRequest { request: _, value, offset: _, responder } => {
                        let _ = responder.send(WriteRequestResponse { response: RequestResponse::Success });

                        if !is_receiving {
                            *file_accepted.lock().unwrap() = None;
                            let _ = ui_handle.upgrade_in_event_loop(|ui| {
                                ui.set_transfer_decision_made(false);
                                ui.set_receiving_file(true);
                                ui.set_transfer_progress(0.05);
                            });
                            is_receiving = true;
                        }
                        
                        received_data.extend_from_slice(&value);
                        if received_data.len() >= 9 {
                            let mut size_bytes = [0u8; 8];
                            size_bytes.copy_from_slice(&received_data[0..8]);
                            let expected_file_size = u64::from_le_bytes(size_bytes) as usize;
                            let name_len = received_data[8] as usize;
                            let header_size = 9 + name_len;
                            let total_expected_size = header_size + expected_file_size;
                            let received_so_far = received_data.len() as f32;
                            let total_size = total_expected_size as f32;
                            let progress = (received_so_far / total_size).min(0.99);
                            let _ = ui_handle.upgrade_in_event_loop(move |ui| {
                                ui.set_transfer_progress(progress);
                            });
                            if received_data.len() >= total_expected_size {
                                let mut filename = String::from_utf8_lossy(&received_data[9..9 + name_len]).to_string();
                                filename = filename.chars().filter(|c| c.is_alphanumeric() || *c == '.' || *c == '-' || *c == '_').collect();
                                if filename.is_empty() { filename = "RustDrop_Received_File".to_string(); }
                                
                                let end_idx = std::cmp::min(header_size + expected_file_size, received_data.len());
                                let file_data = &received_data[header_size..end_idx];
                                let mut save_path = dirs::download_dir().unwrap_or_else(|| std::path::PathBuf::from("."));
                                save_path.push(&filename);
                                
                                let file_msg = format!("Received file {} with {} bytes. Saving to {:?}", filename, file_data.len(), save_path);
                                let _ = ui_handle.upgrade_in_event_loop(move |ui| ui.set_transfer_status(file_msg.clone().into()));
                                let start_time = std::time::Instant::now();
                                let mut waiting = 0;
                                while file_accepted.lock().unwrap().is_none() && waiting < 300 {
                                    tokio::time::sleep(std::time::Duration::from_millis(100)).await;
                                    waiting += 1;
                                }
                                
                                let _ = ui_handle.upgrade_in_event_loop(|ui| ui.set_transfer_progress(1.0));
                                
                                let elapsed = start_time.elapsed().as_secs_f32();
                                if elapsed < 1.0 {
                                    tokio::time::sleep(std::time::Duration::from_secs_f32(1.0 - elapsed)).await;
                                }
                                
                                let message = if let Some(true) = *file_accepted.lock().unwrap() {
                                    if let Err(e) = std::fs::write(&save_path, file_data) {
                                        format!("Error saving file: {}", e)
                                    } else {
                                        format!("File saved successfully to {:?}", save_path)
                                    }
                                } else {
                                    "File transfer rejected or timed out".to_string()
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
                                is_receiving = false;
                                received_data.clear();
                            }
                        }
                    }
                    _ => {}
                }
            },
            None => {
                let _ = ui_handle.upgrade_in_event_loop(|ui| ui.set_transfer_status("Channel closed".into()));
                is_receiving = false;
                received_data.clear();
                break;
            }
        }
    }
}

#[cfg(not(target_os = "android"))]
pub(crate) async fn bluetooth(ui_handle: slint::Weak<AppWindow>) {
    let tokio_handle = tokio::runtime::Handle::current();
    let adapter = Arc::new(Adapter::default().await.ok_or("Bluetooth adapter not found").unwrap());
    let adapter_ui = Arc::clone(&adapter);
    let adapter_disconnect = Arc::clone(&adapter);
    let adapter_timeout = adapter.wait_available();
    if let Err(_) = timeout(Duration::from_secs(1), adapter_timeout).await {
        let _ = ui_handle.upgrade_in_event_loop(|ui| ui.set_transfer_status("Please check whether your device supports Bluetooth, or if your Bluetooth is turned off.".into()));
        return;
    }
    let _ = ui_handle.upgrade_in_event_loop(|ui| ui.set_transfer_status("Starting Bluetooth scan...".into()));
    let mut scan = adapter.scan(&[TARGET_SERVICE]).await.unwrap();
    let _ = ui_handle.upgrade_in_event_loop(|ui| ui.set_transfer_status("Scan started".into()));
    let is_scanning = Arc::new(Mutex::new(true));
    let is_scanning_stop = Arc::clone(&is_scanning);
    let is_scanning_start = Arc::clone(&is_scanning);
    let ui_handle_clone = ui_handle.clone();
    let identifier_name = Arc::new(Mutex::new(HashMap::<String, Device>::new()));
    let identifier_name_map = Arc::clone(&identifier_name);
    let ui_handle_request = ui_handle.clone();
    let active_session = Arc::new(Mutex::new(None));
    let disconnect_session = Arc::clone(&active_session);
    let ui_handle_for_callbacks = ui_handle_request.clone();
    let ui_handle_for_disconnect = ui_handle.clone();
    let tokio_handle_disconnect = tokio_handle.clone();
    let _ = ui_handle_request.upgrade_in_event_loop(move |ui| {
        let identifier_name = Arc::clone(&identifier_name);
        ui.on_send_select_device_blue(move |identifier: SharedString| {
            let adapter_connect = Arc::clone(&adapter_ui);
            let identifier_name = Arc::clone(&identifier_name);
            let active_session_clone = Arc::clone(&active_session);
            let ui_handle_spawn = ui_handle_for_callbacks.clone();
            *is_scanning_stop.lock().unwrap() = false;
            tokio_handle.spawn(async move {
                let file = FileDialog::new()
                    .set_directory("/")
                    .pick_file();
                let id_str = identifier.to_string();
                let device = identifier_name.lock().unwrap().get(&id_str).cloned().unwrap();
                let device_file = device.clone();
                if let Some(path) = file {
                    let path_str = path.to_string_lossy().into_owned();
                    let path_str_clone = path_str.clone();
                    let _ = ui_handle_spawn.upgrade_in_event_loop(move |ui| ui.set_transfer_status(format!("Selected file: {}", path_str_clone).into()));
                    tokio::time::sleep(Duration::from_secs(1)).await;
                    match timeout(Duration::from_secs(10), adapter_connect.connect_device(&device_file)).await {
                        Ok(Ok(_)) => {
                            let identifier_msg = identifier.to_string();
                            let _ = ui_handle_spawn.upgrade_in_event_loop(move |ui| ui.set_transfer_status(format!("Connected to {}", identifier_msg).into()));
                            *active_session_clone.lock().unwrap() = Some(device);
                            tokio::time::sleep(Duration::from_secs(1)).await;
                            let _ = ui_handle_spawn.upgrade_in_event_loop(|ui| ui.set_transfer_status("About to send file".into()));
                            let success = send_file_blue(&adapter_connect, &device_file, &path_str, ui_handle_spawn.clone()).await;
                            if success {
                                let _ = ui_handle_spawn.upgrade_in_event_loop(|ui| ui.set_transfer_status("Sent file successfully".into()));
                            } else {
                                let _ = ui_handle_spawn.upgrade_in_event_loop(|ui| ui.set_transfer_status("Failed to send file".into()));
                            }
                        }
                        Ok(Err(e)) => {
                            let err_msg = format!("Connect error: {}", e);
                            let _ = ui_handle_spawn.upgrade_in_event_loop(move |ui| ui.set_transfer_status(err_msg.clone().into()));
                        }
                        Err(_) => {
                            let _ = ui_handle_spawn.upgrade_in_event_loop(|ui| ui.set_transfer_status("Connect timed out".into()));
                        }
                    }
                }
            });
        });
        ui.on_disconnect(move || {
            if let Some(session) = disconnect_session.lock().unwrap().take() {
                let device = session.clone();
                let adapter_async = Arc::clone(&adapter_disconnect);
                let is_scanning_start = Arc::clone(&is_scanning_start);
                let ui_handle_disconnect_inner = ui_handle_for_disconnect.clone();
                tokio_handle_disconnect.spawn(async move {
                    adapter_async.disconnect_device(&device).await.unwrap();
                    let _ = ui_handle_disconnect_inner.upgrade_in_event_loop(|ui| ui.set_transfer_status("Disconnected".into()));
                    *is_scanning_start.lock().unwrap() = true;
                });
            }
        });
    });
    if *is_scanning.lock().unwrap() {
        let _ = ui_handle.upgrade_in_event_loop(|ui| ui.set_transfer_status("Scan started".into()));
        loop {
            if !*is_scanning.lock().unwrap() {
                let _ = ui_handle.upgrade_in_event_loop(|ui| ui.set_transfer_status("Scan stopped".into()));
                break;
            }
            match timeout(Duration::from_millis(500), scan.next()).await {
                Ok(Some(discovered_device)) => {
                    if !discovered_device.adv_data.services.contains(&TARGET_SERVICE) {
                        continue;
                    }
                    let blue_data = BlueData {
                        identifier: format!("{}", discovered_device.device.name().as_deref().unwrap_or("(unknown)")),
                        signal_strength: discovered_device.rssi.map(|x| format!(" ({}dBm)", x)).unwrap_or_default(),
                        service_uuid: discovered_device.adv_data.services
                    };
                    let hashmap_identifier = discovered_device.device.clone();
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
                Ok(None) => break,
                Err(_) => continue,
            }
        }
    }
}