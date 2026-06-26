#[cfg(not(target_os = "android"))]
mod bluetooth;
mod wifi;

use mdns_sd::ServiceDaemon;
use mdns_sd::Error;
use bluest::*;
use mimalloc::MiMalloc;

#[global_allocator]
static GLOBAL: MiMalloc = MiMalloc;

#[cfg(not(target_os = "android"))]
slint::include_modules!();


#[cfg(not(target_os = "android"))]
#[async_std::main]
async fn main() -> Result<(), Error> {
    let ui = AppWindow::new().unwrap();
    let mdns = ServiceDaemon::new().expect("Failed to create daemon");
    let ui_clone = ui.as_weak();

    let file_accepted = std::sync::Arc::new(std::sync::Mutex::new(None));
    let file_accepted_clone = std::sync::Arc::clone(&file_accepted);
    ui.on_file_accept(move || {
        *file_accepted_clone.lock().unwrap() = Some(true);
    });

    let file_rejected_clone = std::sync::Arc::clone(&file_accepted);
    ui.on_file_reject(move || {
        *file_rejected_clone.lock().unwrap() = Some(false);
    });

    let ui_blue_recv = ui_clone.clone();
    let file_accepted_blue = std::sync::Arc::clone(&file_accepted);
    std::thread::spawn(move || {
        tokio::runtime::Runtime::new()
            .unwrap()
            .block_on(bluetooth::receive_file_blue(ui_blue_recv, file_accepted_blue));
    });

    let file_accepted_wifi = std::sync::Arc::clone(&file_accepted);
    
    // Start Bluetooth scanning
    let ui_blue_scan = ui_clone.clone();
    std::thread::spawn(move || {
        tokio::runtime::Runtime::new()
            .unwrap()
            .block_on(bluetooth::bluetooth(ui_blue_scan));
    });

    // Start WiFi scanning
    let mdns_clone = mdns.clone();
    let ui_wifi_scan = ui_clone.clone();
    let file_acc_wifi = std::sync::Arc::clone(&file_accepted_wifi);
    async_std::task::spawn(async move {
        wifi::wifi(mdns_clone, ui_wifi_scan, file_acc_wifi).await;
    });

    ui.run().expect("UI Initialization Error");
    Ok(())
}