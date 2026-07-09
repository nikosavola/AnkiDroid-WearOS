//! Protobuf wire format for the due batch. The schema is defined as `prost`-annotated Rust structs
//! and the codec is derived — no `protoc` and no `build.rs`, so it builds anywhere the crate does.
//! `sync.rs` wraps [`encode_due_batch`]/[`decode_due_batch`] in zstd for the actual transfer.

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
pub fn encode_due_batch(batch: WearosDueBatch) -> Vec<u8> {
    DueBatchPb::from(batch).encode_to_vec()
}

/// Decode protobuf bytes produced by [`encode_due_batch`].
pub fn decode_due_batch(bytes: &[u8]) -> Result<WearosDueBatch, CoreError> {
    DueBatchPb::decode(bytes)
        .map(WearosDueBatch::from)
        .map_err(|e| CoreError::SerializationError { error_message: e.to_string() })
}
