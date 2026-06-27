# RustDrop

This is an airdrop clone built in Rust, supporting file sharing over __Wifi__ and __Bluetooth__.
This project is based on the Bluest crate for Bluetooth, and several different crates to get Wifi working. This project uses tokio for asynchronous timing and it uses thread management. This project also uses Arc to manage a lot of the variables.
