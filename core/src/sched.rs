//! Offline per-grade interval previews from the deck's own scheduling config.
//!
//! The phone ships AnkiDroid's real per-button interval labels with the due batch, and those are
//! exact. They go stale the moment a card is graded and requeued inside the session, though: the
//! requeued entry keeps the labels fetched before the grade, so a card that advanced a learning
//! step or graduated still shows its pre-grade numbers. AnkiDroid's own scheduler is not reachable
//! from the watch, so this module recomputes those labels from what the deck config exposes
//! (`new.delays`, `lapse.delays`, `new.ints`, `rev.*`, and the FSRS weights + desired retention).
//!
//! The scheduling itself is Anki's: rslib's `scheduler/states` (vendored verbatim from Anki 26.05
//! under `core/vendor/anki`, compiled by the `anki-states` crate) decides every interval. This module
//! is the adapter around it, doing what rslib's `scheduler/answering` does with a stored card:
//! build the card's current state (`current.rs`), the FSRS outcomes and fuzz factor (`mod.rs`), and
//! write the chosen next state back into card fields (`learning.rs`, `review.rs`, `relearning.rs`).
//! AnkiDroid still owns the real schedule: these previews only feed the watch UI and the session's
//! requeue decision, and every grade is reconciled by AnkiDroid on write-back.
//!
//! What the watch cannot reproduce: Anki's load balancer (when enabled it picks a less busy day
//! inside the fuzz window from other cards' due dates, so day+ labels can be a day or two off), the
//! day-rollover conversion of long learning steps (`maybe_as_days`), and a memory state Anki would
//! rebuild from the revlog for a card that has none. Without a fuzz seed (older phone build)
//! intervals stay unfuzzed.

use crate::models::CoreError;
use anki_states::{
    CardState, FsrsMemoryState, INITIAL_EASE_FACTOR, LearnState, NewState, NormalState,
    RelearnState, ReviewState, SchedulingConfig, SchedulingStates,
};
use fsrs::{FSRS, MemoryState, NextStates};
use rand::rngs::StdRng;
use rand::{Rng, SeedableRng};
use serde::{Deserialize, Serialize};
use serde_json::Value;

pub const SECS_PER_DAY: f64 = 86_400.0;

/// Scheduling config for one deck, as AnkiDroid exposes it through `FlashCardsContract.Deck.OPTIONS`.
///
/// That payload is the legacy schema11 deck-config dict. Every field is best-effort: a missing key
/// falls back to Anki's own default rather than failing the sync.
#[derive(uniffi::Record, Clone, Debug, Serialize, Deserialize)]
pub struct DeckScheduleConfig {
    /// Learning steps in seconds (`new.delays`, stored in minutes in the legacy dict).
    pub learn_steps: Vec<f64>,
    /// Relearning steps in seconds (`lapse.delays`).
    pub relearn_steps: Vec<f64>,
    /// Graduating interval in days (`new.ints[0]`).
    pub graduating_interval_days: i32,
    /// Easy interval in days (`new.ints[1]`).
    pub easy_interval_days: i32,
    /// Hard multiplier for review cards (`rev.hardFactor`).
    pub hard_multiplier: f64,
    /// Interval multiplier for review cards (`rev.ivlFct`).
    pub interval_multiplier: f64,
    /// Easy bonus for review cards (`rev.ease4`).
    pub easy_bonus: f64,
    /// Longest interval in days (`rev.maxIvl`).
    pub max_interval_days: i32,
    /// New-interval multiplier on a lapse (`lapse.mult`).
    pub lapse_multiplier: f64,
    /// Minimum interval in days after a lapse (`lapse.minInt`).
    pub lapse_min_interval_days: i32,
    /// FSRS weights: 21 for FSRS-6, 19 or 17 for older presets (the `fsrs` crate fills shorter
    /// lists to 21 the way rslib does), from `fsrs_params_6`, falling back to
    /// `fsrs_params_5`/`fsrsWeights`. A list of any other length means FSRS is off for this deck
    /// and SM-2 factors apply instead.
    pub fsrs_weights: Vec<f64>,
    /// Desired retention as a fraction (0.9). 0.0 means unset; Anki's default applies.
    pub desired_retention: f64,
    /// New cards per day (`new.perDay`). Clamped to >= 0; Anki's default 20 when absent.
    #[uniffi(default = 20)]
    pub new_per_day: i32,
    /// Maximum reviews per day (`rev.perDay`). Clamped to >= 0; Anki's default 200 when absent.
    #[uniffi(default = 200)]
    pub reviews_per_day: i32,
    /// Where new cards go relative to reviews (`newMix`): 0 mixed in, 1 after, 2 before.
    #[uniffi(default = 0)]
    #[serde(default)]
    pub new_mix: i32,
    /// Where interday learning cards go relative to reviews (`interdayLearningMix`), same values.
    #[uniffi(default = 0)]
    #[serde(default)]
    pub interday_learning_mix: i32,
}

impl Default for DeckScheduleConfig {
    /// Anki's stock deck-config defaults, used when a key is absent from the exposed JSON.
    fn default() -> Self {
        Self {
            learn_steps: vec![60.0, 600.0],
            relearn_steps: vec![600.0],
            graduating_interval_days: 1,
            easy_interval_days: 4,
            hard_multiplier: 1.2,
            interval_multiplier: 1.0,
            easy_bonus: 1.3,
            max_interval_days: 36_500,
            lapse_multiplier: 0.0,
            lapse_min_interval_days: 1,
            fsrs_weights: Vec::new(),
            desired_retention: 0.9,
            new_per_day: 20,
            reviews_per_day: 200,
            new_mix: REVIEW_MIX_MIXED,
            interday_learning_mix: REVIEW_MIX_MIXED,
        }
    }
}

#[uniffi::export]
impl DeckScheduleConfig {
    /// Whether the deck schedules with FSRS (a usable weight list) rather than SM-2.
    pub fn fsrs_enabled(&self) -> bool {
        matches!(self.fsrs_weights.len(), 17 | 19 | 21)
    }
}

/// The scheduler state of one card, read from `FlashCardsContract.Card`. Decides which branch of the
/// scheduler produces the card's previews.
#[derive(uniffi::Record, Clone, Debug, Default, Serialize, Deserialize)]
pub struct CardScheduleState {
    /// Anki's card *queue*: 0 = new, 1 = learning (which is also where a relearning card sits),
    /// 2 = review, 3 = day-learning. Only used to tell whether the card is in today's queue; the
    /// step ladder it follows comes from [card_type].
    pub queue: i32,
    /// Anki's card *type*: 0 = new, 1 = learning, 2 = review, 3 = relearning. This, not the queue,
    /// decides learn vs relearn steps (a lapsed card has queue 1 but type 3) and which FSRS state
    /// the card is in. -1 when the phone did not send it.
    pub card_type: i32,
    /// Current interval in days (0 for new and learning cards).
    pub interval_days: f64,
    /// Index into the deck's step list for learning/relearning cards; -1 when unknown.
    pub learning_step: i32,
    /// The card's SM-2 ease factor (`sm2_factor` / 1000, e.g. 2.5). 0.0 means unknown, in which
    /// case Anki's initial 2.5 applies.
    pub ease_factor: f64,
    /// FSRS memory state, 0.0 when the card has none (new card, or FSRS off).
    pub stability: f64,
    pub difficulty: f64,
    /// Days since the card was last reviewed.
    pub elapsed_days: f64,
    /// Epoch seconds of the last review; 0 = unknown, in which case [`elapsed_days`] is what the
    /// phone sent.
    #[uniffi(default = 0)]
    pub last_review_secs: i64,
    /// rslib's fuzz seed for the card's next answer (card id + reps), which makes Anki's interval
    /// fuzz reproducible. `None` when the phone didn't send `reps`; intervals then stay unfuzzed.
    #[uniffi(default = None)]
    #[serde(default)]
    pub fuzz_seed: Option<i64>,
}

/// Parse AnkiDroid's deck `OPTIONS` JSON (rslib's legacy schema11 dict) into a schedule config.
///
/// Keys are read individually rather than deserialized into a strict struct, so a missing or
/// malformed key falls back to Anki's default instead of failing the whole sync. AnkiDroid writes
/// the FSRS keys in camelCase (`fsrsParams6`, `desiredRetention`); the snake_case spellings are
/// still accepted.
#[uniffi::export]
pub fn parse_deck_schedule_config(options_json: String) -> Result<DeckScheduleConfig, CoreError> {
    let root: Value =
        serde_json::from_str(&options_json).map_err(|e| CoreError::SerializationError {
            error_message: format!("deck options are not valid JSON: {e}"),
        })?;
    let defaults = DeckScheduleConfig::default();

    let new = root.get("new");
    let rev = root.get("rev");
    let lapse = root.get("lapse");

    // `new.delays` and `lapse.delays` are minutes as floats; the watch works in seconds.
    let learn_steps =
        minutes_to_secs(new.and_then(|v| v.get("delays"))).unwrap_or(defaults.learn_steps);
    let relearn_steps =
        minutes_to_secs(lapse.and_then(|v| v.get("delays"))).unwrap_or(defaults.relearn_steps);

    // `new.ints` is a tuple [graduating, easy] in days (AnkiDroid has written 2- and 3-element forms).
    let ints = new.and_then(|v| v.get("ints")).and_then(Value::as_array);
    let graduating_interval_days = ints
        .and_then(|a| a.first())
        .and_then(number_as_i32)
        .unwrap_or(defaults.graduating_interval_days);
    let easy_interval_days =
        ints.and_then(|a| a.get(1)).and_then(number_as_i32).unwrap_or(defaults.easy_interval_days);

    Ok(DeckScheduleConfig {
        learn_steps,
        relearn_steps,
        graduating_interval_days,
        easy_interval_days,
        hard_multiplier: read_f64(rev, "hardFactor").unwrap_or(defaults.hard_multiplier),
        interval_multiplier: read_f64(rev, "ivlFct").unwrap_or(defaults.interval_multiplier),
        easy_bonus: read_f64(rev, "ease4").unwrap_or(defaults.easy_bonus),
        max_interval_days: read_i32(rev, "maxIvl").unwrap_or(defaults.max_interval_days),
        lapse_multiplier: read_f64(lapse, "mult").unwrap_or(defaults.lapse_multiplier),
        lapse_min_interval_days: read_i32(lapse, "minInt")
            .unwrap_or(defaults.lapse_min_interval_days),
        fsrs_weights: read_fsrs_weights(&root),
        desired_retention: read_desired_retention(&root).unwrap_or(defaults.desired_retention),
        new_per_day: read_per_day(new, defaults.new_per_day),
        reviews_per_day: read_per_day(rev, defaults.reviews_per_day),
        new_mix: read_review_mix(&root, "newMix"),
        interday_learning_mix: read_review_mix(&root, "interdayLearningMix"),
    })
}

/// Anki's `ReviewMix` values for `newMix` / `interdayLearningMix`.
pub const REVIEW_MIX_MIXED: i32 = 0;
pub const REVIEW_MIX_AFTER: i32 = 1;
pub const REVIEW_MIX_BEFORE: i32 = 2;

/// A `ReviewMix` key from the top level of the schema11 dict; anything unknown is Anki's default.
fn read_review_mix(root: &Value, key: &str) -> i32 {
    root.get(key)
        .and_then(number_as_i32)
        .filter(|v| matches!(*v, REVIEW_MIX_AFTER | REVIEW_MIX_BEFORE))
        .unwrap_or(REVIEW_MIX_MIXED)
}

/// Read a per-day limit from a schema11 `new`/`rev` object (`perDay`), clamped to >= 0. A missing or
/// negative value falls back to the default rather than dropping the whole sync.
fn read_per_day(obj: Option<&Value>, default: i32) -> i32 {
    obj.and_then(|v| v.get("perDay")).and_then(number_as_i32).filter(|v| *v >= 0).unwrap_or(default)
}

/// FSRS weights in preference order: v6, then v5, then the v4 key (`fsrsWeights`).
fn read_fsrs_weights(root: &Value) -> Vec<f64> {
    for key in ["fsrsParams6", "fsrs_params_6", "fsrsParams5", "fsrs_params_5", "fsrsWeights"] {
        if let Some(arr) = root.get(key).and_then(Value::as_array)
            && !arr.is_empty()
        {
            let weights: Vec<f64> = arr.iter().filter_map(Value::as_f64).collect();
            if !weights.is_empty() {
                return weights;
            }
        }
    }
    Vec::new()
}

/// Desired retention as a fraction. Anki stores 0.9; some surfaces express it as a percentage.
fn read_desired_retention(root: &Value) -> Option<f64> {
    let raw = ["desiredRetention", "desired_retention"]
        .iter()
        .find_map(|key| root.get(*key).and_then(Value::as_f64))?;
    if raw <= 0.0 {
        return None;
    }
    Some(if raw > 1.0 { raw / 100.0 } else { raw })
}

fn read_f64(obj: Option<&Value>, key: &str) -> Option<f64> {
    obj?.get(key)?.as_f64().filter(|v| v.is_finite())
}

/// Read a JSON number that schema11 stores as a float but means as an integer (e.g. `maxIvl`).
fn read_i32(obj: Option<&Value>, key: &str) -> Option<i32> {
    read_f64(obj, key).map(|v| v.round() as i32)
}

fn number_as_i32(v: &Value) -> Option<i32> {
    let f = v.as_f64()?;
    Some(f.round() as i32)
}

/// Read a JSON number array of minutes into seconds, ignoring non-numeric entries.
fn minutes_to_secs(v: Option<&Value>) -> Option<Vec<f64>> {
    let arr = v?.as_array()?;
    Some(
        arr.iter()
            .filter_map(Value::as_f64)
            .filter(|m| m.is_finite() && *m >= 0.0)
            .map(|m| m * 60.0)
            .collect(),
    )
}

/// Format an interval in seconds the way Anki labels its answer buttons ("45s", "10m", "1.5h",
/// "4d", "1.2mo", "2.1y"), via rslib's `answer_button_time`.
#[uniffi::export]
pub fn format_interval_label(seconds: f64) -> String {
    anki_states::answer_button_time(seconds as f32)
}

/// Per-grade intervals, in seconds, ordered Again, Hard, Good, Easy.
///
/// This is the semantic form: the session uses it to decide whether a grade is a learning step,
/// and [`preview_labels`] formats it for display. Like rslib's `describe_next_states`, each value
/// is the chosen state's interval: seconds for a learning step, whole days otherwise.
#[uniffi::export]
pub fn preview_interval_secs(
    config: &DeckScheduleConfig,
    state: &CardScheduleState,
    now_unix_ms: i64,
) -> Vec<f64> {
    let next = next_states(config, state, now_unix_ms);
    [next.again, next.hard, next.good, next.easy]
        .into_iter()
        .map(|choice| f64::from(anki_states::interval_secs(choice)))
        .collect()
}

/// The previews as display labels in ease order (Again, Hard, Good, Easy).
#[uniffi::export]
pub fn preview_labels(
    config: &DeckScheduleConfig,
    state: &CardScheduleState,
    now_unix_ms: i64,
) -> Vec<String> {
    preview_interval_secs(config, state, now_unix_ms)
        .iter()
        .map(|secs| format_interval_label(*secs))
        .collect()
}

/// The previews as display labels in the order of the card's own buttons.
///
/// This is the shape AnkiDroid ships and the watch UI renders against: one label per button, so a
/// 3-button card (Again/Good/Easy, no Hard) gets three. Computed previews are always four values in
/// ease order, so a card whose previews were recomputed would otherwise hand the UI a different
/// shape than a card still carrying AnkiDroid's labels.
#[uniffi::export]
pub fn preview_labels_for_buttons(
    config: &DeckScheduleConfig,
    state: &CardScheduleState,
    now_unix_ms: i64,
    button_count: i32,
) -> Vec<String> {
    let labels = preview_labels(config, state, now_unix_ms);
    match button_count {
        // Again / Good / Easy: the Hard slot (index 1) is dropped for a 3-button card.
        3 => vec![labels[0].clone(), labels[2].clone(), labels[3].clone()],
        2 => vec![labels[0].clone(), labels[2].clone()],
        // Anything else (including the 4-button default) keeps all four.
        _ => labels,
    }
}

/// The card's scheduler state after answering with `ease`, as Anki would store it.
///
/// The session applies this to a card it is about to requeue so the requeued copy carries previews
/// for the state the card is actually in, instead of the labels fetched before the grade.
#[uniffi::export]
pub fn advance_state(
    config: &DeckScheduleConfig,
    state: &CardScheduleState,
    ease: i32,
    now_unix_ms: i64,
) -> CardScheduleState {
    let next = next_states(config, state, now_unix_ms);
    let chosen = match ease {
        1 => next.again,
        2 => next.hard,
        4 => next.easy,
        _ => next.good,
    };
    let advanced = match chosen {
        CardState::Normal(normal) => apply_state(config, state, normal),
        // Only reachable in a filtered deck, which the context never claims to be.
        CardState::Filtered(_) => state.clone(),
    };
    // The card was just reviewed, so no time has elapsed on the requeued copy, and Anki's next fuzz
    // seed counts the extra rep.
    CardScheduleState { elapsed_days: 0.0, fuzz_seed: state.fuzz_seed.map(|s| s + 1), ..advanced }
}

/// rslib's next states for this card: the four answers from its current state.
fn next_states(
    config: &DeckScheduleConfig,
    state: &CardScheduleState,
    now_unix_ms: i64,
) -> SchedulingStates {
    let scheduling = SchedulingConfig {
        learn_steps_mins: steps_in_minutes(&config.learn_steps),
        relearn_steps_mins: steps_in_minutes(&config.relearn_steps),
        graduating_interval_good: non_negative(config.graduating_interval_days),
        graduating_interval_easy: non_negative(config.easy_interval_days),
        // The legacy dict's `new.initialFactor` isn't parsed; Anki's stock 2.5 applies.
        initial_ease_factor: INITIAL_EASE_FACTOR,
        hard_multiplier: config.hard_multiplier as f32,
        easy_multiplier: config.easy_bonus as f32,
        interval_multiplier: config.interval_multiplier as f32,
        maximum_review_interval: non_negative(config.max_interval_days),
        // Only decides the leech flag, which nothing here reads; Anki's default.
        leech_threshold: 8,
        lapse_multiplier: config.lapse_multiplier as f32,
        minimum_lapse_interval: non_negative(config.lapse_min_interval_days),
        fuzz_factor: fuzz_factor(state),
        fsrs_next_states: fsrs_next_states(config, state),
        fsrs_short_term_with_steps_enabled: false,
        fsrs_allow_short_term: fsrs_allow_short_term(config),
    };
    anki_states::next_states(current_card_state(config, state, now_unix_ms), &scheduling)
}

/// Which of Anki's card types the stored state is.
#[derive(Clone, Copy)]
enum CardKind {
    New,
    Learn,
    Review,
    Relearn,
}

/// The card's type, by Anki's `ctype` when the phone sent one. Anki never keeps a new card outside
/// the new queue, so a type-0 card in another queue is taken at the queue's word, and the queue is
/// all there is when no type was sent.
fn card_kind(state: &CardScheduleState) -> CardKind {
    match (state.card_type, state.queue) {
        (3, _) => CardKind::Relearn,
        (2, _) | (0 | -1, 2) => CardKind::Review,
        (1, _) | (0 | -1, 1 | 3) => CardKind::Learn,
        (0, _) | (-1, 0) => CardKind::New,
        _ => CardKind::Learn,
    }
}

/// rslib answering/current.rs `normal_study_state`, from the fields the provider exposes.
///
/// rslib reads the learning position from `left % 1000` (steps remaining); the phone ships the step
/// index derived from it, so this turns it back into steps remaining on the ladder the card type
/// selects. An unknown index (-1) counts as the first step. A review card's elapsed days follow
/// current.rs, which clamps the due day to today, so a card is never treated as answered early.
fn current_card_state(
    config: &DeckScheduleConfig,
    state: &CardScheduleState,
    now_unix_ms: i64,
) -> CardState {
    let interval = state.interval_days.round().max(0.0) as u32;
    let ease_factor = ease_factor(state);
    let memory_state = (state.stability > 0.0).then_some(FsrsMemoryState {
        stability: state.stability as f32,
        difficulty: state.difficulty as f32,
    });
    let elapsed_secs = if state.last_review_secs > 0 {
        u32::try_from(now_unix_ms / 1000 - state.last_review_secs).unwrap_or(0)
    } else {
        0
    };
    let learn_state = |steps: &[f64]| {
        let steps_mins = steps_in_minutes(steps);
        let remaining_steps = remaining_steps(steps.len(), state.learning_step);
        LearnState {
            scheduled_secs: anki_states::current_delay_secs(&steps_mins, remaining_steps),
            remaining_steps,
            elapsed_secs,
            memory_state,
        }
    };
    match card_kind(state) {
        CardKind::New => NewState { position: 0 }.into(),
        CardKind::Learn => learn_state(&config.learn_steps).into(),
        CardKind::Review => ReviewState {
            scheduled_days: interval,
            elapsed_days: (elapsed_days(state) as u32).max(interval),
            ease_factor,
            lapses: 0,
            leeched: false,
            memory_state,
        }
        .into(),
        CardKind::Relearn => RelearnState {
            learning: learn_state(&config.relearn_steps),
            review: ReviewState {
                scheduled_days: interval,
                elapsed_days: interval,
                ease_factor,
                lapses: 0,
                leeched: false,
                memory_state,
            },
        }
        .into(),
    }
}

/// rslib answering/{learning,review,relearning}.rs `apply_*_state`: the chosen state written back
/// into the fields the watch keeps. A learning state leaves the interval and ease alone, as Anki
/// does; a missing memory state clears the stored one, as Anki does.
fn apply_state(
    config: &DeckScheduleConfig,
    state: &CardScheduleState,
    next: NormalState,
) -> CardScheduleState {
    let with_memory = |memory: Option<FsrsMemoryState>| CardScheduleState {
        stability: memory.map_or(0.0, |m| f64::from(m.stability)),
        difficulty: memory.map_or(0.0, |m| f64::from(m.difficulty)),
        ..state.clone()
    };
    match next {
        NormalState::New(_) => {
            CardScheduleState { queue: 0, card_type: 0, learning_step: 0, ..with_memory(None) }
        }
        NormalState::Learning(learn) => CardScheduleState {
            queue: learning_queue(learn.scheduled_secs),
            card_type: 1,
            learning_step: step_index(config.learn_steps.len(), learn.remaining_steps),
            ..with_memory(learn.memory_state)
        },
        NormalState::Review(review) => CardScheduleState {
            queue: 2,
            card_type: 2,
            interval_days: f64::from(review.scheduled_days),
            learning_step: -1,
            ease_factor: stored_ease(review.ease_factor),
            ..with_memory(review.memory_state)
        },
        NormalState::Relearning(relearn) => CardScheduleState {
            queue: learning_queue(relearn.learning.scheduled_secs),
            card_type: 3,
            interval_days: f64::from(relearn.review.scheduled_days),
            learning_step: step_index(config.relearn_steps.len(), relearn.learning.remaining_steps),
            ease_factor: stored_ease(relearn.review.ease_factor),
            ..with_memory(relearn.learning.memory_state)
        },
    }
}

/// Anki's learn queue (1) for an intraday step, its day-learn queue (3) for a step of a day or
/// more. rslib decides this against the collection's next rollover, which the watch doesn't have.
const fn learning_queue(scheduled_secs: u32) -> i32 {
    if scheduled_secs < 86_400 { 1 } else { 3 }
}

/// Steps remaining for a card on step `index` of a ladder of `len` steps (rslib's `left % 1000`).
fn remaining_steps(len: usize, index: i32) -> u32 {
    let index = usize::try_from(index).unwrap_or(0);
    u32::try_from(len.saturating_sub(index)).unwrap_or(u32::MAX)
}

/// The inverse of [`remaining_steps`], clamped onto the ladder the way the phone derives it.
fn step_index(len: usize, remaining_steps: u32) -> i32 {
    if len == 0 {
        return -1;
    }
    let remaining = (remaining_steps % 1000) as usize;
    len.saturating_sub(remaining).min(len - 1) as i32
}

/// rslib stores the ease factor as permille (`(ease * 1000).round() as u16`).
fn stored_ease(ease_factor: f32) -> f64 {
    (f64::from(ease_factor) * 1000.0).round() / 1000.0
}

/// rslib takes steps in minutes; the config keeps seconds.
fn steps_in_minutes(steps: &[f64]) -> Vec<f32> {
    steps.iter().map(|secs| (secs / 60.0) as f32).collect()
}

fn non_negative(value: i32) -> u32 {
    u32::try_from(value).unwrap_or(0)
}

/// FSRS's four outcomes for the card, as rslib's `card_state_updater` computes them (answering
/// mod.rs), or `None` when the deck schedules with SM-2 or its weights are unusable.
///
/// A card with no memory state gets FSRS's initial values. rslib would first rebuild one from the
/// revlog for a non-new card; the watch has no revlog, so such a card is treated as fresh.
fn fsrs_next_states(config: &DeckScheduleConfig, state: &CardScheduleState) -> Option<NextStates> {
    if !config.fsrs_enabled() {
        return None;
    }
    let weights: Vec<f32> = config.fsrs_weights.iter().map(|w| *w as f32).collect();
    let fsrs = FSRS::new(&weights).ok()?;
    // Retention outside what Anki's options screen allows would only come from a malformed dict.
    let retention = if config.desired_retention > 0.0 { config.desired_retention } else { 0.9 };
    let memory_state = (state.stability > 0.0).then_some(MemoryState {
        stability: state.stability as f32,
        difficulty: state.difficulty as f32,
    });
    let days_elapsed = u32::try_from(elapsed_days(state)).unwrap_or(0);
    fsrs.next_states(memory_state, retention.clamp(0.7, 0.99) as f32, days_elapsed).ok()
}

/// rslib's `fsrs_allow_short_term` (answering mod.rs): FSRS may schedule sub-day intervals itself
/// only when the weights' short-term parameters are on.
fn fsrs_allow_short_term(config: &DeckScheduleConfig) -> bool {
    let weights = &config.fsrs_weights;
    if weights.len() >= 19 { weights[17] > 0.0 && weights[18] > 0.0 } else { weights.is_empty() }
}

/// rslib's `get_fuzz_factor`: a uniform draw in `0..1` from the card's seed, so the same card at the
/// same rep count always fuzzes the same way. `None` without a seed.
fn fuzz_factor(state: &CardScheduleState) -> Option<f32> {
    state.fuzz_seed.map(|seed| StdRng::seed_from_u64(seed as u64).random_range(0.0..1.0))
}

/// How many whole Anki days have passed since the card was last reviewed.
///
/// Callers fill `elapsed_days` from day numbers ([`crate::day::elapsed_days_between`]), so this is
/// already whole. Rounding only matters for an older phone build that still sends a wall-clock
/// fraction.
fn elapsed_days(state: &CardScheduleState) -> i64 {
    state.elapsed_days.max(0.0).round() as i64
}

/// The card's SM-2 ease factor, or Anki's initial 2.5 when the card carries none.
fn ease_factor(state: &CardScheduleState) -> f32 {
    if state.ease_factor > 0.0 { state.ease_factor as f32 } else { INITIAL_EASE_FACTOR }
}
