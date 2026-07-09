//! Protobuf wire format for the due batch. The schema is defined as `prost`-annotated Rust structs
//! and the codec is derived — no `protoc` and no `build.rs`, so it builds anywhere the crate does.
//! `sync.rs` wraps [`encode_due_batch`]/[`decode_due_batch`] in zstd for the actual transfer.

use crate::models::{CoreError, WearosAnswer, WearosCard, WearosDueBatch};
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

#[derive(Clone, PartialEq, prost::Message)]
struct AnswerPb {
    #[prost(string, tag = "1")]
    id: String,
    #[prost(int64, tag = "2")]
    note_id: i64,
    #[prost(int32, tag = "3")]
    ord: i32,
    #[prost(int32, tag = "4")]
    ease: i32,
    #[prost(int64, tag = "5")]
    time_taken_ms: i64,
}

#[derive(Clone, PartialEq, prost::Message)]
struct AnswersPb {
    #[prost(message, repeated, tag = "1")]
    answers: Vec<AnswerPb>,
}

impl From<WearosAnswer> for AnswerPb {
    fn from(a: WearosAnswer) -> Self {
        Self {
            id: a.id,
            note_id: a.note_id,
            ord: a.ord,
            ease: a.ease,
            time_taken_ms: a.time_taken_ms,
        }
    }
}

impl From<AnswerPb> for WearosAnswer {
    fn from(a: AnswerPb) -> Self {
        Self {
            id: a.id,
            note_id: a.note_id,
            ord: a.ord,
            ease: a.ease,
            time_taken_ms: a.time_taken_ms,
        }
    }
}

/// Encode buffered answers as protobuf bytes (infallible; prost encoding cannot fail).
pub fn encode_answers(answers: Vec<WearosAnswer>) -> Vec<u8> {
    AnswersPb { answers: answers.into_iter().map(AnswerPb::from).collect() }.encode_to_vec()
}

/// Decode protobuf bytes produced by [`encode_answers`].
pub fn decode_answers(bytes: &[u8]) -> Result<Vec<WearosAnswer>, CoreError> {
    AnswersPb::decode(bytes)
        .map(|pb| pb.answers.into_iter().map(WearosAnswer::from).collect())
        .map_err(|e| CoreError::SerializationError { error_message: e.to_string() })
}
