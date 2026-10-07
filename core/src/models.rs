//! Shared data models and the error type crossing the UniFFI boundary.

use crate::sched::{CardScheduleState, DeckScheduleConfig};
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
    /// Absolute Anki day number (`day_index`) this card is due. `None` means today's batch from the
    /// phone's scheduler, or a new card.
    #[uniffi(default = None)]
    pub due_day: Option<i64>,
    /// Epoch seconds the card is due within its day, for intraday learning cards only.
    #[uniffi(default = None)]
    pub due_at_secs: Option<i64>,
    /// The card's scheduler state, used to recompute interval previews offline once the labels
    /// above go stale (a card graded and requeued within the session). `None` on older phone
    /// builds and for the mock fallback, where the shipped labels are all there is.
    #[uniffi(default = None)]
    pub state: Option<CardScheduleState>,
}

#[derive(uniffi::Record, Serialize, Deserialize, Clone)]
pub struct WearosDueBatch {
    pub deck_id: i64,
    pub deck_name: String,
    pub cards: Vec<WearosCard>,
    /// Anki's "next day starts at" hour for this batch (default 4am local time), so the watch can
    /// decide what is due each day offline with the same boundary the phone used.
    #[uniffi(default = 4)]
    pub rollover_hour: i32,
    /// When the phone fetched this batch, epoch milliseconds; 0 = unknown.
    #[uniffi(default = 0)]
    pub fetched_at_ms: i64,
    /// The deck's scheduling config (learning steps, graduation intervals, FSRS weights) from
    /// AnkiDroid's deck `OPTIONS`. `None` when the phone could not read it, in which case previews
    /// stay as AnkiDroid shipped them.
    #[uniffi(default = None)]
    pub schedule_config: Option<DeckScheduleConfig>,
}

#[derive(uniffi::Record, Serialize, Deserialize, Clone)]
pub struct WearosAnswer {
    pub id: String,
    pub note_id: i64,
    pub ord: i32,
    pub ease: i32,
    pub time_taken_ms: i64,
    /// Deck this card was reviewed in, so the phone can select the right deck before answering when a
    /// buffer spans multiple decks (reviewed offline, synced together). 0 = unknown (older buffers /
    /// cross-version wire): the phone falls back to whatever deck AnkiDroid currently has selected.
    pub deck_id: i64,
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

// `?`-conversions so the db/proto/sync layers propagate their native errors as the right CoreError
// variant instead of hand-writing `.map_err(|e| CoreError::..)` at every call site. The message is
// preserved verbatim via `to_string()`, matching what those closures produced.
impl From<rusqlite::Error> for CoreError {
    fn from(e: rusqlite::Error) -> Self {
        Self::DatabaseError { error_message: e.to_string() }
    }
}

impl From<prost::DecodeError> for CoreError {
    fn from(e: prost::DecodeError) -> Self {
        Self::SerializationError { error_message: e.to_string() }
    }
}

// zstd (de)compression surfaces failures as std::io::Error; the core has no other raw io::Error
// source, so mapping it to SerializationError can't mis-classify anything else.
impl From<std::io::Error> for CoreError {
    fn from(e: std::io::Error) -> Self {
        Self::SerializationError { error_message: e.to_string() }
    }
}
