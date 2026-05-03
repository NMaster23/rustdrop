use mdns::resolve;
use mdns_sd::{ServiceDaemon, ServiceInfo, ServiceEvent};
use async_std::stream::StreamExt;
use mdns_sd::Error;
use bluest::*;
use local_ip_address::local_ip;
use slint::{SharedString, VecModel};
use std::collections::HashMap;
use std::net::{TcpListener, TcpStream};
use std::fs::File;
use std::io;
use slint::Model;
use std::rc::Rc;

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
            match io::copy(&mut file, &mut stream) {
                Ok(bytes) => println!("Sent {} bytes successfully", bytes),
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
                let mut file = File::create("received_file.part").unwrap();
                match io::copy(&mut socket, &mut file) {
                    Ok(bytes) => println!("Received {} bytes and saved to 'received_file.part'", bytes),
                    Err(e) => println!("Error during reception: {}", e),
                }
            }
            Err(e) => println!("Connection error: {e:?}"),
        }
    }
}

async fn bluetooth() {
    let adapter = Adapter::default().await.ok_or("Bluetooth adapter not found").unwrap();
        adapter.wait_available().await.unwrap();
        println!("starting scan");
        let mut scan = adapter.scan(&[]).await.unwrap();
        println!("scan started");
        while let Some(discovered_device) = scan.next().await {
            let blue_data = BlueData {
                identifier: discovered_device.device.name().as_deref().unwrap_or("(unknown)").to_string(),
                signal_strength: discovered_device.rssi.map(|x| format!(" ({}dBm)", x)).unwrap_or_default(),
                service_uuid: discovered_device.adv_data.services
            };
            println!("{}", blue_data.identifier);
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
    async_std::task::spawn(async move {
        while let Ok(event) = receiver.recv() {
            if let ServiceEvent::ServiceResolved(resolved) = event {
                println!("Resolved a new service: {}", resolved.fullname);
                let name = resolved.get_hostname().to_string();
                let ip = resolved.get_addresses().into_iter().map(|a| a.to_string()).next().unwrap_or_default();
                ui_handle.upgrade_in_event_loop(move |ui| {
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
//    println!("Enter file path to send:");
//    let mut file_path = String::new();
//   io::stdin().read_line(&mut file_path).expect("failed to readline");
 //  println!("Enter target IP address:");
//    let mut ip_addr = String::new();
//    io::stdin().read_line(&mut ip_addr).expect("failed to readline");
//    send_file_wifi(ip_addr.trim().to_string(), 5200, file_path.trim());
}

#[async_std::main]
async fn main() -> Result<(), Error> {
    let ui = AppWindow::new().unwrap();
    let mdns = ServiceDaemon::new().expect("Failed to create daemon");
    let ui_clone = ui.as_weak();
    ui.on_send_mode(move |blue_or_wifi: bool| {
        if blue_or_wifi {
            async_std::task::spawn(bluetooth());
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