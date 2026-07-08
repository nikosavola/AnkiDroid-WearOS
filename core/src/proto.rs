//! Experimental protobuf wire format for the due batch, parallel to the JSON path in `sync.rs`.
//!
//! Backlog: "Protobufs/FlatBuffers over the Data Layer instead of JSON ... shrinks payloads (helps
//! the DATA_ITEM_TOO_LARGE margin further)." This defines the schema as annotated Rust structs and
//! derives the codec with `prost` — no `protoc` and no `build.rs`, so it builds anywhere the crate
//! does. It is intentionally NOT wired into the Kotlin sync path yet: ship it behind the existing
//! `serialize_due_batch`/`deserialize_due_batch` once it's validated end-to-end (see
//! HANDOFF_TESTING.md). Kept a separate export so the migration can be staged and A/B measured.

// Generated codec + our thin conversions don't need to satisfy every pedantic/nursery lint the
// hand-written core holds itself to; this module is experimental scaffolding.
#![allow(clippy::pedantic, clippy::nursery)]

use crate::models::{CoreError, WearosCard, WearosDueBatch};
use prost::Message;

#[derive(Clone, PartialEq, prost::Message)]
struct CardPb {
    #[prost(int64, tag = "1")]
    id: i64,
    #[prost(int64, tag = "2")]
    note_id: i64,
    #[prost(int32, tag = "3")]
    ord: i32,
    #[prost(string, tag = "4")]
    front: String,
    #[prost(string, tag = "5")]
    back: String,
    #[prost(int32, tag = "6")]
    button_count: i32,
    #[prost(string, repeated, tag = "7")]
    next_review_times: Vec<String>,
}

#[derive(Clone, PartialEq, prost::Message)]
struct DueBatchPb {
    #[prost(int64, tag = "1")]
    deck_id: i64,
    #[prost(string, tag = "2")]
    deck_name: String,
    #[prost(message, repeated, tag = "3")]
    cards: Vec<CardPb>,
}

impl From<WearosCard> for CardPb {
    fn from(c: WearosCard) -> Self {
        Self {
            id: c.id,
            note_id: c.note_id,
            ord: c.ord,
            front: c.front,
            back: c.back,
            button_count: c.button_count,
            next_review_times: c.next_review_times,
        }
    }
}

impl From<CardPb> for WearosCard {
    fn from(c: CardPb) -> Self {
        Self {
            id: c.id,
            note_id: c.note_id,
            ord: c.ord,
            front: c.front,
            back: c.back,
            button_count: c.button_count,
            next_review_times: c.next_review_times,
        }
    }
}

impl From<WearosDueBatch> for DueBatchPb {
    fn from(b: WearosDueBatch) -> Self {
        Self {
            deck_id: b.deck_id,
            deck_name: b.deck_name,
            cards: b.cards.into_iter().map(CardPb::from).collect(),
        }
    }
}

impl From<DueBatchPb> for WearosDueBatch {
    fn from(b: DueBatchPb) -> Self {
        Self {
            deck_id: b.deck_id,
            deck_name: b.deck_name,
            cards: b.cards.into_iter().map(WearosCard::from).collect(),
        }
    }
}

/// Encode a due batch as protobuf bytes (infallible; prost encoding cannot fail).
#[uniffi::export]
pub fn serialize_due_batch_proto(batch: WearosDueBatch) -> Vec<u8> {
    DueBatchPb::from(batch).encode_to_vec()
}

/// Decode protobuf bytes produced by [`serialize_due_batch_proto`].
#[uniffi::export]
pub fn deserialize_due_batch_proto(bytes: Vec<u8>) -> Result<WearosDueBatch, CoreError> {
    DueBatchPb::decode(&bytes[..])
        .map(WearosDueBatch::from)
        .map_err(|e| CoreError::SerializationError { error_message: e.to_string() })
}
