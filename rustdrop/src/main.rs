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
    async_std::task::spawn(bluetooth::receive_file_blue());
    ui.on_send_mode(move |blue_or_wifi: bool| {
        if blue_or_wifi {
            let blue_ui = ui_clone.clone();
            async_std::task::spawn(bluetooth::bluetooth(blue_ui));
        }
        if !blue_or_wifi {
            let mdns_clone = mdns.clone();
            let wifi_ui = ui_clone.clone();
            async_std::task::spawn(wifi::wifi(mdns_clone, wifi_ui));
        }
    });
    ui.run().expect("UI Initialization Error");
    Ok(())
}

#[cfg(target_os = "android")]
fn main() {
    println!("Running on Android");
}