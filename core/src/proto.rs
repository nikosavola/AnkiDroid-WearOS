//! Protobuf wire format for the due batch. The schema is defined as `prost`-annotated Rust structs
//! and the codec is derived — no `protoc` and no `build.rs`, so it builds anywhere the crate does.
//! `sync.rs` wraps [`encode_due_batch`]/[`decode_due_batch`] in zstd for the actual transfer.

use crate::models::{CoreError, DeckInfo, WearosAnswer, WearosCard, WearosDueBatch};
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
    Ok(DueBatchPb::decode(bytes)?.into())
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
    // Additive field: an old encoder omits it (decodes to 0), an old decoder ignores it, so watch and
    // phone stay wire-compatible across versions. 0 => unknown deck (phone falls back to selected).
    #[prost(int64, tag = "6")]
    deck_id: i64,
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
            deck_id: a.deck_id,
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
            deck_id: a.deck_id,
        }
    }
}

/// Encode buffered answers as protobuf bytes (infallible; prost encoding cannot fail).
pub fn encode_answers(answers: Vec<WearosAnswer>) -> Vec<u8> {
    AnswersPb { answers: answers.into_iter().map(AnswerPb::from).collect() }.encode_to_vec()
}

/// Decode protobuf bytes produced by [`encode_answers`].
pub fn decode_answers(bytes: &[u8]) -> Result<Vec<WearosAnswer>, CoreError> {
    Ok(AnswersPb::decode(bytes)?.answers.into_iter().map(WearosAnswer::from).collect())
}

#[derive(Clone, PartialEq, prost::Message)]
struct DeckPb {
    #[prost(int64, tag = "1")]
    id: i64,
    #[prost(string, tag = "2")]
    full_name: String,
}

#[derive(Clone, PartialEq, prost::Message)]
struct DecksPb {
    #[prost(message, repeated, tag = "1")]
    decks: Vec<DeckPb>,
}

impl From<DeckInfo> for DeckPb {
    fn from(d: DeckInfo) -> Self {
        Self { id: d.id, full_name: d.full_name }
    }
}

impl From<DeckPb> for DeckInfo {
    fn from(d: DeckPb) -> Self {
        Self { id: d.id, full_name: d.full_name }
    }
}

/// Encode the watch-visible deck list as protobuf bytes (infallible; prost encoding cannot fail).
pub fn encode_decks(decks: Vec<DeckInfo>) -> Vec<u8> {
    DecksPb { decks: decks.into_iter().map(DeckPb::from).collect() }.encode_to_vec()
}

/// Decode protobuf bytes produced by [`encode_decks`].
pub fn decode_decks(bytes: &[u8]) -> Result<Vec<DeckInfo>, CoreError> {
    Ok(DecksPb::decode(bytes)?.decks.into_iter().map(DeckInfo::from).collect())
}
