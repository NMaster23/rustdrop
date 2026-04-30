use mdns_sd::{ServiceDaemon, ServiceInfo, ServiceEvent};
use std::io;
use mdns_sd::Error;
use std::collections::HashMap;

#[async_std::main]
async fn main() -> Result<(), Error> {
    //let mut mdns_data = HashMap::new();
    println!("Enter Mode ([B]roadcast or [D]iscover)");
    let mut input = String::new();
    io::stdin().read_line(&mut input).expect("failed to readline");
    let service_type = "_rustdrop._udp.local.";
    let instance_name = "rustdrop";
    let host_name = "rustdrop.local.";
    let port = 5200;
    let mdns = ServiceDaemon::new().expect("Failed to create daemon");
    let receiver = mdns.browse(service_type).expect("Failed to browse");
    let receiver_info = receiver.clone();
    std::thread::spawn(move || {
        while let Ok(event) = receiver_info.recv() {
            match event {
                ServiceEvent::ServiceResolved(resolved) => {
                    println!("Resolved a new service: {}", resolved.fullname);
                }
                other_event => {
                    println!("Received other event: {:?}", &other_event);
                }
            }
        }
    });
    let rustdrop_service = ServiceInfo::new(
        service_type,
        instance_name,
        host_name,
        "",
        port,
        None,
    ).unwrap();
    Ok(())
}