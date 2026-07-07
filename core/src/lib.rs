uniffi::setup_scaffolding!();

#[derive(uniffi::Record)]
pub struct WearosCard {
    pub id: i64,
    pub front: String,
    pub back: String,
}

#[uniffi::export]
pub fn greet_watch(name: String) -> String {
    format!("Hello, {}! From Rust core.", name)
}

#[uniffi::export]
pub fn get_sample_card() -> WearosCard {
    WearosCard {
        id: 42,
        front: "What is AnkiDroid WearOS?".to_string(),
        back: "A smartwatch companion app written with Rust core and Kotlin UI.".to_string(),
    }
}
