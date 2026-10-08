//! Anki's own scheduler state machine, compiled from rslib sources vendored verbatim under
//! `core/vendor/anki` (see `UPSTREAM.toml` there; `scripts/vendor-rslib.sh --check` guards them).
//!
//! The vendored files reach into the rest of rslib through `crate::...` paths. This crate provides
//! exactly those paths. Plain data types (ids, `FsrsMemoryState`, `RevlogReviewKind`,
//! `PreviewDelays`) are real; everything that would touch a collection, its storage, or the load
//! balancer only has to type-check, and its bodies are `unreachable!()` because the public API
//! below never builds a load balancer context or a `Collection`.

// Upstream code under its own lints and style; our pedantic set would only flag the vendored
// files and the Collection/load-balancer paths we compile but never call.
#![allow(
    dead_code,
    unused,
    clippy::all,
    clippy::pedantic,
    clippy::nursery,
    reason = "vendored rslib code and type-check-only stubs"
)]

// timespan.rs imports `anki_i18n::I18n` from its sibling crate; point that name back here.
extern crate self as anki_i18n;

#[rustfmt::skip]
#[path = "../../vendor/anki/rslib/src/scheduler/states/mod.rs"]
mod states;

#[rustfmt::skip]
#[path = "../../vendor/anki/rslib/src/scheduler/timespan.rs"]
mod timespan;

pub use card::FsrsMemoryState;
pub use states::review::{
    EASE_FACTOR_AGAIN_DELTA, EASE_FACTOR_EASY_DELTA, EASE_FACTOR_HARD_DELTA, INITIAL_EASE_FACTOR,
    MINIMUM_EASE_FACTOR,
};
pub use states::{
    CardState, FilteredState, LearnState, NewState, NormalState, PreviewState, RelearnState,
    ReschedulingFilterState, ReviewState, SchedulingStates,
};

use states::interval_kind::IntervalKind;
use states::steps::LearningSteps;
use states::StateContext;

/// The deck-config and per-answer inputs rslib's `CardStateUpdater::state_context` reads
/// (answering/mod.rs), owned so callers need not hold rslib's borrowed types.
#[derive(Debug, Clone)]
pub struct SchedulingConfig {
    /// Learning steps in minutes, as rslib stores them.
    pub learn_steps_mins: Vec<f32>,
    /// Relearning steps in minutes.
    pub relearn_steps_mins: Vec<f32>,
    pub graduating_interval_good: u32,
    pub graduating_interval_easy: u32,
    pub initial_ease_factor: f32,
    pub hard_multiplier: f32,
    pub easy_multiplier: f32,
    pub interval_multiplier: f32,
    pub maximum_review_interval: u32,
    pub leech_threshold: u32,
    pub lapse_multiplier: f32,
    pub minimum_lapse_interval: u32,
    /// In `0.0..1.0`, from rslib's `get_fuzz_factor`; `None` leaves intervals unfuzzed.
    pub fuzz_factor: Option<f32>,
    /// FSRS's four outcomes for the card; `None` when the deck uses SM-2.
    pub fsrs_next_states: Option<fsrs::NextStates>,
    pub fsrs_short_term_with_steps_enabled: bool,
    pub fsrs_allow_short_term: bool,
}

/// rslib's `CardState::next_states` with a context built like `state_context`, for a card in a
/// normal deck with no load balancer.
pub fn next_states(current: CardState, config: &SchedulingConfig) -> SchedulingStates {
    let ctx = StateContext {
        fuzz_factor: config.fuzz_factor,
        fsrs_next_states: config.fsrs_next_states.clone(),
        fsrs_short_term_with_steps_enabled: config.fsrs_short_term_with_steps_enabled,
        fsrs_allow_short_term: config.fsrs_allow_short_term,
        steps: LearningSteps::new(&config.learn_steps_mins),
        graduating_interval_good: config.graduating_interval_good,
        graduating_interval_easy: config.graduating_interval_easy,
        initial_ease_factor: config.initial_ease_factor,
        hard_multiplier: config.hard_multiplier,
        easy_multiplier: config.easy_multiplier,
        interval_multiplier: config.interval_multiplier,
        maximum_review_interval: config.maximum_review_interval,
        leech_threshold: config.leech_threshold,
        load_balancer_ctx: None,
        relearn_steps: LearningSteps::new(&config.relearn_steps_mins),
        lapse_multiplier: config.lapse_multiplier,
        minimum_lapse_interval: config.minimum_lapse_interval,
        in_filtered_deck: false,
        preview_delays: scheduler::answering::PreviewDelays::default(),
    };
    current.next_states(&ctx)
}

/// The delay of the step a learning card with `remaining_steps` is on, in seconds (rslib's
/// `LearningSteps::current_delay_secs`, which answering/current.rs uses for `scheduled_secs`).
pub fn current_delay_secs(steps_mins: &[f32], remaining_steps: u32) -> u32 {
    LearningSteps::new(steps_mins).current_delay_secs(remaining_steps)
}

/// The state's interval in seconds, as `describe_next_states` reads it (minus the rollover
/// adjustment, which needs the collection's day cutoff).
pub fn interval_secs(state: CardState) -> u32 {
    state.interval_kind().as_seconds()
}

/// The state's interval in whole days, or `None` for a seconds-based (learning step) interval.
pub fn interval_days(state: CardState) -> Option<u32> {
    match state.interval_kind() {
        IntervalKind::InDays(days) => Some(days),
        IntervalKind::InSecs(_) => None,
    }
}

/// rslib's answer-button label ("10m", "4d", "1.5mo") with Anki's English template strings.
pub fn answer_button_time(seconds: f32) -> String {
    timespan::answer_button_time(seconds, &I18n)
}

/// Stand-in for `anki_i18n::I18n` carrying only the English `scheduling-answer-button-time-*`
/// templates (ftl/core/scheduling.ftl). The f32 Display impl prints the shortest form ("1", "1.1"),
/// which is how Fluent renders these amounts.
pub struct I18n;

impl I18n {
    pub fn scheduling_answer_button_time_seconds(&self, amount: f32) -> String {
        format!("{amount}s")
    }

    pub fn scheduling_answer_button_time_minutes(&self, amount: f32) -> String {
        format!("{amount}m")
    }

    pub fn scheduling_answer_button_time_hours(&self, amount: f32) -> String {
        format!("{amount}h")
    }

    pub fn scheduling_answer_button_time_days(&self, amount: f32) -> String {
        format!("{amount}d")
    }

    pub fn scheduling_answer_button_time_months(&self, amount: f32) -> String {
        format!("{amount}mo")
    }

    pub fn scheduling_answer_button_time_years(&self, amount: f32) -> String {
        format!("{amount}y")
    }

    pub fn scheduling_end(&self) -> String {
        unreachable!("answer_button_time_collapsible is not used")
    }

    pub fn scheduling_time_span_seconds(&self, _amount: f32) -> String {
        unreachable!("time_span is not used")
    }

    pub fn scheduling_time_span_minutes(&self, _amount: f32) -> String {
        unreachable!("time_span is not used")
    }

    pub fn scheduling_time_span_hours(&self, _amount: f32) -> String {
        unreachable!("time_span is not used")
    }

    pub fn scheduling_time_span_days(&self, _amount: f32) -> String {
        unreachable!("time_span is not used")
    }

    pub fn scheduling_time_span_months(&self, _amount: f32) -> String {
        unreachable!("time_span is not used")
    }

    pub fn scheduling_time_span_years(&self, _amount: f32) -> String {
        unreachable!("time_span is not used")
    }
}

mod prelude {
    pub(crate) use crate::card::CardId;
    pub(crate) use crate::collection::Collection;
    pub(crate) use crate::deckconfig::DeckConfig;
    pub(crate) use crate::decks::DeckId;
    pub(crate) use crate::error::{AnkiError, OrNotFound, Result};
    pub(crate) use crate::timestamp::TimestampSecs;
}

mod card {
    use std::fmt;

    use crate::decks::DeckId;

    #[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
    pub struct CardId(pub i64);

    impl fmt::Display for CardId {
        fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
            write!(f, "{}", self.0)
        }
    }

    /// rslib card/mod.rs.
    #[derive(Debug, Clone, Copy, PartialEq)]
    pub struct FsrsMemoryState {
        pub stability: f32,
        pub difficulty: f32,
    }

    impl From<fsrs::MemoryState> for FsrsMemoryState {
        fn from(value: fsrs::MemoryState) -> Self {
            Self { stability: value.stability, difficulty: value.difficulty }
        }
    }

    impl From<FsrsMemoryState> for fsrs::MemoryState {
        fn from(value: FsrsMemoryState) -> Self {
            Self { stability: value.stability, difficulty: value.difficulty }
        }
    }

    pub(crate) struct Card {
        pub deck_id: DeckId,
        pub original_deck_id: DeckId,
    }

    impl Card {
        pub(crate) fn get_fuzz_factor(&self, _for_reschedule: bool) -> Option<f32> {
            unreachable!("only reached through Collection")
        }
    }
}

mod decks {
    use std::fmt;

    use crate::deckconfig::DeckConfigId;

    #[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
    pub struct DeckId(pub i64);

    impl fmt::Display for DeckId {
        fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
            write!(f, "{}", self.0)
        }
    }

    pub(crate) struct Deck;

    impl Deck {
        pub(crate) fn config_id(&self) -> Option<DeckConfigId> {
            unreachable!("only reached through Collection")
        }
    }
}

mod deckconfig {
    #[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
    pub struct DeckConfigId(pub i64);

    pub struct DeckConfig {
        pub inner: DeckConfigInner,
    }

    pub struct DeckConfigInner {
        pub maximum_review_interval: u32,
        pub easy_days_percentages: Vec<f32>,
    }
}

mod notes {
    #[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
    pub struct NoteId(pub i64);
}

mod revlog {
    /// rslib revlog/mod.rs.
    #[derive(Debug, Clone, Copy, PartialEq, Eq)]
    pub enum RevlogReviewKind {
        Learning = 0,
        Review = 1,
        Relearning = 2,
        Filtered = 3,
        Manual = 4,
        Rescheduled = 5,
    }
}

mod error {
    use std::fmt;

    pub type Result<T, E = AnkiError> = std::result::Result<T, E>;

    #[derive(Debug)]
    pub enum AnkiError {
        InvalidInput(InvalidInputError),
    }

    impl From<InvalidInputError> for AnkiError {
        fn from(value: InvalidInputError) -> Self {
            Self::InvalidInput(value)
        }
    }

    #[derive(Debug)]
    pub struct InvalidInputError {
        pub message: String,
        pub source: Option<Box<dyn std::error::Error + Send + Sync>>,
        pub backtrace: Option<std::backtrace::Backtrace>,
    }

    pub(crate) trait OrNotFound<T> {
        fn or_not_found(self, identifier: impl fmt::Display) -> Result<T>;
    }

    impl<T> OrNotFound<T> for Option<T> {
        fn or_not_found(self, _identifier: impl fmt::Display) -> Result<T> {
            unreachable!("only reached through Collection")
        }
    }
}

mod timestamp {
    #[derive(Debug, Clone, Copy)]
    pub struct TimestampSecs(pub i64);

    impl TimestampSecs {
        pub(crate) fn adding_secs(self, _secs: i64) -> Self {
            unreachable!("only reached through the load balancer")
        }

        pub(crate) fn local_datetime(self) -> crate::error::Result<chrono::NaiveDate> {
            unreachable!("only reached through the load balancer")
        }
    }
}

mod storage {
    use std::collections::HashMap;

    use crate::card::{Card, CardId};
    use crate::deckconfig::{DeckConfig, DeckConfigId};
    use crate::decks::{Deck, DeckId};
    use crate::error::Result;
    use crate::notes::NoteId;

    pub struct SqliteStorage {
        _private: (),
    }

    impl SqliteStorage {
        pub(crate) fn get_card(&self, _id: CardId) -> Result<Option<Card>> {
            unreachable!("no collection storage")
        }

        pub(crate) fn get_deck(&self, _id: DeckId) -> Result<Option<Deck>> {
            unreachable!("no collection storage")
        }

        pub(crate) fn get_all_cards_due_in_range(
            &self,
            _min_day: u32,
            _max_day: u32,
        ) -> Result<Vec<Vec<(CardId, NoteId, DeckId)>>> {
            unreachable!("no collection storage")
        }

        pub(crate) fn get_deck_config_map(&self) -> Result<HashMap<DeckConfigId, DeckConfig>> {
            unreachable!("no collection storage")
        }
    }
}

mod collection {
    use crate::deckconfig::{DeckConfig, DeckConfigId};
    use crate::decks::DeckId;
    use crate::error::Result;
    use crate::storage::SqliteStorage;

    pub struct Collection {
        pub(crate) storage: SqliteStorage,
    }

    impl Collection {
        pub(crate) fn home_deck_config(
            &self,
            _config_id: Option<DeckConfigId>,
            _home_deck_id: DeckId,
        ) -> Result<DeckConfig> {
            unreachable!("no collection")
        }
    }
}

mod scheduler {
    pub(crate) mod answering {
        /// rslib scheduler/answering/mod.rs.
        #[derive(Debug, Default)]
        pub(crate) struct PreviewDelays {
            pub again: u32,
            pub hard: u32,
            pub good: u32,
        }
    }
}
