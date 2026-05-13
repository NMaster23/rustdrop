#[cfg(target_os = "android")]
#[no_mangle]
fn android_main(app: slint::android::AndroidApp) {
    slint::android::init(app).unwrap();
    let ui = AppWindow::new().unwrap();
    let mdns = ServiceDaemon::new().expect("Failed to create daemon");
    let ui_clone = ui.as_weak();
    async_std::task::spawn(receive_file_blue());
    
    ui.on_send_mode(move |blue_or_wifi: bool| {
        if blue_or_wifi {
            let blue_ui = ui_clone.clone();
            async_std::task::spawn(bluetooth(blue_ui));
        } else {
            let mdns_clone = mdns.clone();
            let wifi_ui = ui_clone.clone();
            async_std::task::spawn(wifi(mdns_clone, wifi_ui));
        }
    });
    ui.run().expect("UI Initialization Error");
}