//! Link the Android `.so` for 16 KB pages. Android 15+ devices may use 16 KB pages, and Android 17
//! also checks that GNU_RELRO starts and ends on a 16 KB boundary. Done here rather than in
//! `.cargo/config.toml` because any `RUSTFLAGS` in the environment silently replaces config flags.

fn main() {
    println!("cargo:rerun-if-changed=build.rs");
    if std::env::var("CARGO_CFG_TARGET_OS").as_deref() == Ok("android") {
        for arg in [
            "-Wl,-z,max-page-size=16384",
            "-Wl,-z,common-page-size=16384",
            "-Wl,-z,separate-loadable-segments",
        ] {
            println!("cargo:rustc-cdylib-link-arg={arg}");
        }
    }
}
