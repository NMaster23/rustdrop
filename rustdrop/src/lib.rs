#![cfg(target_os = "android")]

mod bluetooth;
mod wifi;

uniffi::setup_scaffolding!();

#[derive(uniffi::Record)]
pub struct BlueData {
    pub identifier: String,
    pub signal_strength: String,
    pub service_uuid: Vec<String>,
}

#[uniffi::export(with_foreign)]
pub trait RustDropUiCallback: Send + Sync {
    fn on_device_discovered(&self, device: BlueData);
}