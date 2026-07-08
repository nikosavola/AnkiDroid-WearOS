//! Shared data models and the error type crossing the UniFFI boundary.

use serde::{Deserialize, Serialize};

#[derive(uniffi::Record, Serialize, Deserialize, Clone)]
pub struct WearosCard {
    pub id: i64,
    pub note_id: i64,
    pub ord: i32,
    pub front: String,
    pub back: String,
    pub button_count: i32,
    pub next_review_times: Vec<String>,
}

#[derive(uniffi::Record, Serialize, Deserialize, Clone)]
pub struct WearosDueBatch {
    pub deck_id: i64,
    pub deck_name: String,
    pub cards: Vec<WearosCard>,
}

#[derive(uniffi::Record, Serialize, Deserialize, Clone)]
pub struct WearosAnswer {
    pub id: String,
    pub note_id: i64,
    pub ord: i32,
    pub ease: i32,
    pub time_taken_ms: i64,
}

/// A flat deck as AnkiDroid exposes it: an id plus a `::`-separated full name. Fed to
/// [`crate::deck_children`] to drive the watch's folder navigation.
#[derive(uniffi::Record, Clone)]
pub struct DeckInfo {
    pub id: i64,
    pub full_name: String,
}

/// One entry in a folder level: either a folder to descend into (`is_folder`) or a studyable deck.
///
/// `id` is the deck id at this exact node, or -1 when a folder has no directly-studyable deck of
/// its own. `path` is the full `::`-joined path (used as the key to descend).
#[derive(uniffi::Record, Clone)]
pub struct DeckNodeInfo {
    pub id: i64,
    pub name: String,
    pub path: String,
    pub is_folder: bool,
}

/// The contents of one folder level in the deck hierarchy.
#[derive(uniffi::Record)]
pub struct DeckLevel {
    /// The deck sitting exactly at the current path, present when the folder itself is studyable
    /// (Anki parent decks are real decks). Null at the root.
    pub own_deck: Option<DeckNodeInfo>,
    /// Immediate children, folders first then decks, each sorted by name.
    pub children: Vec<DeckNodeInfo>,
}

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum CoreError {
    #[error("Database error: {error_message}")]
    DatabaseError { error_message: String },
    #[error("Serialization error: {error_message}")]
    SerializationError { error_message: String },
    #[error("State error: {error_message}")]
    StateError { error_message: String },
}
