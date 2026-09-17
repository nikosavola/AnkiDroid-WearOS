//! Protobuf wire format for the due batch. The schema is defined as `prost`-annotated Rust structs
//! and the codec is derived — no `protoc` and no `build.rs`, so it builds anywhere the crate does.
//! `sync.rs` wraps [`encode_due_batch`]/[`decode_due_batch`] in zstd for the actual transfer.

use crate::models::{CoreError, DeckInfo, WearosAnswer, WearosCard, WearosDueBatch};
use crate::sched::{CardScheduleState, DeckScheduleConfig};
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
    // Additive: absent from older encoders. `None` (not an all-default message) means "the phone
    // did not send state", which callers must distinguish from a genuinely new card's zeros.
    #[prost(message, optional, tag = "8")]
    state: Option<CardStatePb>,
}

/// Scheduler state for one card, read from `FlashCardsContract.Card`.
#[derive(Clone, PartialEq, prost::Message)]
struct CardStatePb {
    #[prost(int32, tag = "1")]
    queue: i32,
    #[prost(double, tag = "2")]
    interval_days: f64,
    #[prost(int32, tag = "3")]
    learning_step: i32,
    #[prost(double, tag = "4")]
    stability: f64,
    #[prost(double, tag = "5")]
    difficulty: f64,
    #[prost(double, tag = "6")]
    elapsed_days: f64,
}

#[derive(Clone, PartialEq, prost::Message)]
struct DueBatchPb {
    #[prost(int64, tag = "1")]
    deck_id: i64,
    #[prost(string, tag = "2")]
    deck_name: String,
    #[prost(message, repeated, tag = "3")]
    cards: Vec<CardPb>,
    // Additive: the deck's scheduling config, absent from older encoders.
    #[prost(message, optional, tag = "4")]
    schedule_config: Option<DeckConfigPb>,
}

/// The deck scheduling config, from AnkiDroid's deck `OPTIONS` JSON.
#[derive(Clone, PartialEq, prost::Message)]
struct DeckConfigPb {
    #[prost(double, repeated, tag = "1")]
    learn_steps: Vec<f64>,
    #[prost(double, repeated, tag = "2")]
    relearn_steps: Vec<f64>,
    #[prost(int32, tag = "3")]
    graduating_interval_days: i32,
    #[prost(int32, tag = "4")]
    easy_interval_days: i32,
    #[prost(double, tag = "5")]
    hard_multiplier: f64,
    #[prost(double, tag = "6")]
    interval_multiplier: f64,
    #[prost(double, tag = "7")]
    easy_bonus: f64,
    #[prost(int32, tag = "8")]
    max_interval_days: i32,
    #[prost(double, tag = "9")]
    lapse_multiplier: f64,
    #[prost(int32, tag = "10")]
    lapse_min_interval_days: i32,
    #[prost(double, repeated, tag = "11")]
    fsrs_weights: Vec<f64>,
    #[prost(double, tag = "12")]
    desired_retention: f64,
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
            state: c.state.map(CardStatePb::from),
        }
    }
}

impl From<CardScheduleState> for CardStatePb {
    fn from(s: CardScheduleState) -> Self {
        Self {
            queue: s.queue,
            interval_days: s.interval_days,
            learning_step: s.learning_step,
            stability: s.stability,
            difficulty: s.difficulty,
            elapsed_days: s.elapsed_days,
        }
    }
}

impl From<CardStatePb> for CardScheduleState {
    fn from(s: CardStatePb) -> Self {
        Self {
            queue: s.queue,
            interval_days: s.interval_days,
            learning_step: s.learning_step,
            stability: s.stability,
            difficulty: s.difficulty,
            elapsed_days: s.elapsed_days,
        }
    }
}

impl From<DeckScheduleConfig> for DeckConfigPb {
    fn from(c: DeckScheduleConfig) -> Self {
        Self {
            learn_steps: c.learn_steps,
            relearn_steps: c.relearn_steps,
            graduating_interval_days: c.graduating_interval_days,
            easy_interval_days: c.easy_interval_days,
            hard_multiplier: c.hard_multiplier,
            interval_multiplier: c.interval_multiplier,
            easy_bonus: c.easy_bonus,
            max_interval_days: c.max_interval_days,
            lapse_multiplier: c.lapse_multiplier,
            lapse_min_interval_days: c.lapse_min_interval_days,
            fsrs_weights: c.fsrs_weights,
            desired_retention: c.desired_retention,
        }
    }
}

impl From<DeckConfigPb> for DeckScheduleConfig {
    fn from(c: DeckConfigPb) -> Self {
        Self {
            learn_steps: c.learn_steps,
            relearn_steps: c.relearn_steps,
            graduating_interval_days: c.graduating_interval_days,
            easy_interval_days: c.easy_interval_days,
            hard_multiplier: c.hard_multiplier,
            interval_multiplier: c.interval_multiplier,
            easy_bonus: c.easy_bonus,
            max_interval_days: c.max_interval_days,
            lapse_multiplier: c.lapse_multiplier,
            lapse_min_interval_days: c.lapse_min_interval_days,
            fsrs_weights: c.fsrs_weights,
            desired_retention: c.desired_retention,
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
            state: c.state.map(CardScheduleState::from),
        }
    }
}

impl From<WearosDueBatch> for DueBatchPb {
    fn from(b: WearosDueBatch) -> Self {
        Self {
            deck_id: b.deck_id,
            deck_name: b.deck_name,
            cards: b.cards.into_iter().map(CardPb::from).collect(),
            schedule_config: b.schedule_config.map(DeckConfigPb::from),
        }
    }
}

impl From<DueBatchPb> for WearosDueBatch {
    fn from(b: DueBatchPb) -> Self {
        Self {
            deck_id: b.deck_id,
            deck_name: b.deck_name,
            cards: b.cards.into_iter().map(WearosCard::from).collect(),
            schedule_config: b.schedule_config.map(DeckScheduleConfig::from),
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
