fn main() {
    slint_build::compile("ui/ui.slint").expect("Slint build failed");
    uniffi::generate_scaffolding("ui/ui.udl").unwrap();
}