#[cfg(target_os = "android")]
mod bluetooth;
#[cfg(target_os = "android")]
mod wifi;

uniffi::setup_scaffolding!();

#[derive(uniffi::Record)]
pub struct BlueData {
    pub identifier: String,
    pub signal_strength: String,
    pub service_uuid: Vec<String>,
}

#[derive(uniffi::Object)]
pub struct RustDropCore {
    callback: std::sync::Mutex<Option<std::sync::Arc<dyn RustDropUiCallback>>>,
}

#[uniffi::export]
impl RustDropCore {
    #[uniffi::constructor]
    pub fn new() -> std::sync::Arc<Self> {
        std::sync::Arc::new(Self {
            callback: std::sync::Mutex::new(None),
        })
    }
    pub fn set_callback(&self, callback: std::sync::Arc<dyn RustDropUiCallback>) {
        let mut guard = self.callback.lock().unwrap();
        *guard = Some(callback);
    }
    pub async fn start_blue(&self) {
        let cb_arc = self.callback.lock().unwrap().clone();
        #[cfg(target_os = "android")]
        bluetooth::bluetooth(cb_arc.unwrap()).await;
    }
}

#[uniffi::export(with_foreign)]
pub trait RustDropUiCallback: Send + Sync {
    fn on_device_discovered(&self, device: BlueData);
}