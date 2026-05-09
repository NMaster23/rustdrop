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
use tokio::runtime::{Builder, Runtime};

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
            let mut decoded = std::path::Path::new(file_path).file_name().unwrap().to_str().unwrap().as_bytes();
            let mut encoded: Vec<u8> = vec![];
            {
                let mut encoder = Encoder::with_chunks_size(&mut encoded, 5);
                encoder.write_all(decoded);
            }
            stream.write_all(&[encoded.len() as u8]).unwrap();
            stream.write_all(&encoded).unwrap();
            match io::copy(&mut file, &mut stream) {
                Ok(bytes) => {
                    println!("Sent {} bytes successfully", bytes); 
                    stream.shutdown(std::net::Shutdown::Both);
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
    println!("Receiver active: Waiting for incoming files...");
    loop {
        match listener.accept() {
            Ok((mut socket, addr)) => {
                println!("Incoming file from: {addr:?}");
                let mut filename = "File.file";
                let file_name = filename.clone();
                let mut file = File::create(filename).unwrap();
                let mut encoded = vec![];
                let mut decoded = String::new();
                let mut decoder = Decoder::new(&encoded as &[u8]);
                decoder.read_to_string(&mut decoded);
                match io::copy(&mut socket, &mut file) {
                    Ok(bytes) => println!("Received {} bytes and saved to '{}'", bytes, file_name),
                    Err(e) => println!("Error during reception: {}", e),
                }
            }
            Err(e) => println!("Connection error: {e:?}"),
        }
    }
}

async fn bluetooth(ui_handle: slint::Weak<AppWindow>) {
    let adapter = Adapter::default().await.ok_or("Bluetooth adapter not found").unwrap();
        adapter.wait_available().await.unwrap();
        println!("starting scan");
        let mut scan = adapter.scan(&[]).await.unwrap();
        println!("scan started");
        let ui_handle_clone = ui_handle.clone();
        while let Some(discovered_device) = scan.next().await {
            let blue_data = BlueData {
                identifier: discovered_device.device.name().as_deref().unwrap_or("(unknown)").to_string(),
                signal_strength: discovered_device.rssi.map(|x| format!(" ({}dBm)", x)).unwrap_or_default(),
                service_uuid: discovered_device.adv_data.services
            };
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
        let ui_handle_request = ui_handle.clone();
        ui_handle_request.upgrade_in_event_loop(move |ui| {
            ui.on_send_select_device_blue(move |identifier: SharedString| {
                let file = FileDialog::new()
                .set_directory("/")
                .pick_file();
                let path_str = file.map(|p| p.to_string_lossy().into_owned()).unwrap_or_default();
            });
        });
}

async fn wifi(mdns: ServiceDaemon, ui_handle: slint::Weak<AppWindow>, bg_thread: Runtime) {
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
    bg_thread.spawn(async move {
        receive_file_wifi();
    });
    let ui_handle_clone = ui_handle.clone();
    bg_thread.spawn(async move {
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
    bg_thread.spawn(async move {
        ui_handle.upgrade_in_event_loop(move |ui| {
        ui.on_send_select_device_wifi(move |device_ip: SharedString| {
            let file = FileDialog::new()
                .set_directory("/")
                .pick_file();
            let path_str = file.map(|p| p.to_string_lossy().into_owned()).unwrap_or_default();
            send_file_wifi(device_ip.to_string(), 5200, &path_str);
            });
        });
    });
}

#[async_std::main]
async fn main() -> Result<(), Error> {
    let ui = AppWindow::new().unwrap();
    let runtime = Builder::new_multi_thread()
        .worker_threads(4)
        .thread_name("my-custom-name")
        .thread_stack_size(3 * 1024 * 1024)
        .build()
        .unwrap();
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
            runtime.spawn(async move {
                let file_thread = Builder::new_multi_thread()
                    .worker_threads(4)
                    .thread_name("my-custom-name")
                    .thread_stack_size(3 * 1024 * 1024)
                    .build()
                    .unwrap();
                async_std::task::block_on(wifi(mdns_clone, wifi_ui, file_thread));    
            });
        }
    });
    ui.run().expect("UI Initialization Error");
    Ok(())
}