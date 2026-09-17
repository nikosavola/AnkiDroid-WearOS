//! Offline per-grade interval previews from the deck's own scheduling config.
//!
//! The phone ships AnkiDroid's real per-button interval labels with the due batch, and those are
//! exact. They go stale the moment a card is graded and requeued inside the session, though: the
//! requeued entry keeps the labels fetched before the grade, so a card that advanced a learning
//! step or graduated still shows its pre-grade numbers. AnkiDroid's own scheduler is not reachable
//! from the watch, so this module recomputes those labels from what the deck config exposes
//! (`new.delays`, `lapse.delays`, `new.ints`, `rev.*`, and the FSRS weights + desired retention).
//!
//! Division of labour with [`crate::session`]: learning steps are a plain ladder over the deck's
//! configured delays, computed here; day-or-longer intervals come from FSRS-6 via `rs-fsrs` when
//! the deck has weights, and from the SM-2 factors otherwise. AnkiDroid still owns the real
//! schedule — these previews only feed the watch UI and the session's requeue decision, and every
//! grade is reconciled by AnkiDroid on write-back.
//!
//! Previews are unfuzzed (`rs-fsrs` fuzz is off) while AnkiDroid's own labels carry fuzz, so the
//! two can differ by a small percentage where a card has not been graded yet. Verifying the
//! numbers against a real collection is tracked in #24; until then treat them as an approximation
//! that is always superseded by AnkiDroid's own write-back.
//!
//! Modelled subset (Anki's basic scheduler): Again always returns to the first step; Hard repeats
//! the current step; Good advances a step and graduates from the last one; Easy graduates
//! immediately. Hard's "average of current step and graduation" refinement and fuzz are not
//! modelled. Numbers are validated against AnkiDroid's own labels by
//! `previews_match_shipped_labels` where a card has not been graded yet.

use crate::models::CoreError;
use rs_fsrs::{Card, FSRS, Parameters, Rating, State};
use serde::{Deserialize, Serialize};
use serde_json::Value;

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
    /// FSRS weights: 19 for FSRS-6 (`fsrs_params_6`, falling back to `fsrs_params_5`/`fsrsWeights`).
    /// Empty means FSRS is off for this deck and SM-2 factors apply instead.
    pub fsrs_weights: Vec<f64>,
    /// Desired retention as a fraction (0.9). 0.0 means unset; Anki's default applies.
    pub desired_retention: f64,
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
        }
    }
}

#[uniffi::export]
impl DeckScheduleConfig {
    /// Whether the deck schedules with FSRS (weights present) rather than SM-2.
    pub fn fsrs_enabled(&self) -> bool {
        !self.fsrs_weights.is_empty()
    }
}

/// The scheduler state of one card, read from `FlashCardsContract.Card`. Decides which branch of the
/// scheduler produces the card's previews.
#[derive(uniffi::Record, Clone, Debug, Default, Serialize, Deserialize)]
pub struct CardScheduleState {
    /// Anki queue: 0 = new, 1 = learning, 2 = review, 3 = relearning.
    pub queue: i32,
    /// Current interval in days (0 for new and learning cards).
    pub interval_days: f64,
    /// Index into the deck's step list for learning/relearning cards; -1 when unknown.
    pub learning_step: i32,
    /// FSRS memory state, 0.0 when the card has none (new card, or FSRS off).
    pub stability: f64,
    pub difficulty: f64,
    /// Days since the card was last reviewed.
    pub elapsed_days: f64,
}

/// Parse AnkiDroid's deck `OPTIONS` JSON (rslib's legacy schema11 dict) into a schedule config.
///
/// The dict mixes conventions — `new`/`rev`/`lapse` are camelCase, while `fsrs_params_6` and
/// `desired_retention` are snake_case — so this reads keys individually rather than deserializing
/// into a strict struct, and a missing or malformed key falls back to Anki's default instead of
/// failing the whole sync.
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
        max_interval_days: read_f64(rev, "maxIvl")
            .and_then(|v| number_as_i32(&serde_json::json!(v)))
            .unwrap_or(defaults.max_interval_days),
        lapse_multiplier: read_f64(lapse, "mult").unwrap_or(defaults.lapse_multiplier),
        lapse_min_interval_days: read_f64(lapse, "minInt")
            .and_then(|v| number_as_i32(&serde_json::json!(v)))
            .unwrap_or(defaults.lapse_min_interval_days),
        fsrs_weights: read_fsrs_weights(&root),
        desired_retention: read_desired_retention(&root).unwrap_or(defaults.desired_retention),
    })
}

/// FSRS weights in preference order: v6, then v5, then the v4 key (`fsrsWeights`).
fn read_fsrs_weights(root: &Value) -> Vec<f64> {
    for key in ["fsrs_params_6", "fsrs_params_5", "fsrsWeights"] {
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
    let raw = root.get("desired_retention").and_then(Value::as_f64)?;
    if raw <= 0.0 {
        return None;
    }
    Some(if raw > 1.0 { raw / 100.0 } else { raw })
}

fn read_f64(obj: Option<&Value>, key: &str) -> Option<f64> {
    obj?.get(key)?.as_f64().filter(|v| v.is_finite())
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

/// Format an interval in seconds the way AnkiDroid's own labels do: "45s", "1m", "10m", "2h",
/// "4d", "3mo", "1y". Sub-minute values round to seconds, so a 30s step reads "30s".
#[uniffi::export]
pub fn format_interval_label(seconds: f64) -> String {
    if seconds < 60.0 {
        return format!("{}s", seconds.round().max(1.0) as i64);
    }
    if seconds < 3600.0 {
        return format!("{}m", (seconds / 60.0).round().max(1.0) as i64);
    }
    if seconds < 86_400.0 {
        return format!("{}h", (seconds / 3600.0).round().max(1.0) as i64);
    }
    let days = seconds / 86_400.0;
    if days < 30.0 {
        return format!("{}d", days.round().max(1.0) as i64);
    }
    if days < 365.0 {
        return format!("{}mo", (days / 30.0).round().max(1.0) as i64);
    }
    format!("{}y", (days / 365.0).round().max(1.0) as i64)
}

/// Per-grade intervals, in seconds, ordered Again, Hard, Good, Easy.
///
/// This is the semantic form: the session uses it to decide whether a grade is a learning step,
/// and [`preview_labels`] formats it for display. `now_unix_ms` is the watch's clock, used only
/// for FSRS elapsed time.
#[uniffi::export]
pub fn preview_interval_secs(
    config: &DeckScheduleConfig,
    state: &CardScheduleState,
    now_unix_ms: i64,
) -> Vec<f64> {
    vec![
        preview_for_grade(config, state, now_unix_ms, 1),
        preview_for_grade(config, state, now_unix_ms, 2),
        preview_for_grade(config, state, now_unix_ms, 3),
        preview_for_grade(config, state, now_unix_ms, 4),
    ]
}

/// The same previews as display labels ("1m", "10m", "4d"). Callers map them onto the card's
/// button count (3-button cards have no Hard).
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

/// Interval in seconds the given ease (1..=4) would schedule, per the deck's own rules.
fn preview_for_grade(
    config: &DeckScheduleConfig,
    state: &CardScheduleState,
    now_unix_ms: i64,
    ease: i32,
) -> f64 {
    let steps = if state.queue == 3 { &config.relearn_steps } else { &config.learn_steps };
    let on_steps = matches!(state.queue, 0 | 1 | 3) || state.interval_days < 1.0;

    if on_steps {
        return learning_preview(config, state, steps, ease);
    }
    if config.fsrs_enabled() {
        return fsrs_preview(config, state, now_unix_ms, ease);
    }
    sm2_review_preview(config, state, ease)
}

/// Learning/relearning branch: Again restarts the ladder, Hard repeats the current step, Good
/// advances (graduating from the last step), Easy graduates immediately.
fn learning_preview(
    config: &DeckScheduleConfig,
    state: &CardScheduleState,
    steps: &[f64],
    ease: i32,
) -> f64 {
    let last_index = steps.len().saturating_sub(1);
    let current = usize::try_from(state.learning_step.max(0)).unwrap_or(0).min(last_index);

    match ease {
        1 => steps.first().copied().unwrap_or(60.0),
        2 => hard_delay_secs(steps, current),
        4 => graduation_secs(config, state, ease),
        _ => {
            if current >= last_index {
                graduation_secs(config, state, ease)
            } else {
                steps.get(current + 1).copied().unwrap_or(60.0)
            }
        }
    }
}

/// Anki's Hard interval on a learning card, in seconds.
///
/// On the first step it is the average of the first two steps, which keeps Hard distinct from Again
/// and below Good; on later steps it simply repeats the current step. With a single step configured
/// it is half again that step, capped at one day more (Anki's rule, so Hard can't exceed Good).
fn hard_delay_secs(steps: &[f64], current: usize) -> f64 {
    let current_secs = steps.get(current).copied().unwrap_or(60.0);
    if current != 0 {
        return current_secs;
    }
    steps.get(1).copied().map_or_else(
        || round_in_days((current_secs * 1.5).min(current_secs + 86_400.0)),
        |next| round_in_days(f64::midpoint(current_secs, next)),
    )
}

/// Anki rounds an interval to whole days once it reaches a day (`maybe_round_in_days`).
fn round_in_days(secs: f64) -> f64 {
    if secs < 86_400.0 {
        return secs;
    }
    (secs / 86_400.0).round() * 86_400.0
}

/// Day-or-longer interval for a graduating (or graduated) button, from FSRS when the deck uses it
/// and from the configured intervals otherwise.
fn graduation_secs(config: &DeckScheduleConfig, state: &CardScheduleState, ease: i32) -> f64 {
    let configured = f64::from(if ease == 4 {
        config.easy_interval_days
    } else {
        config.graduating_interval_days
    });
    let days = if config.fsrs_enabled() {
        // Graduation under FSRS is the FSRS interval for that button. It needs the card's memory
        // state, which AnkiDroid stores from the first answer on: a card that has never been
        // answered (or has none yet) has no FSRS interval to give, so the configured interval
        // stands in. Elapsed time is irrelevant for a card that hasn't just been answered, so the
        // scheduler is asked at the card's own last-review instant.
        fsrs_scheduled_days(config, state, ease, 0).unwrap_or(configured)
    } else if ease == 4 {
        f64::from(config.easy_interval_days)
    } else {
        f64::from(config.graduating_interval_days)
    };
    clamp_days(config, days) * 86_400.0
}

/// FSRS next interval for a review-state card, in seconds.
fn fsrs_preview(
    config: &DeckScheduleConfig,
    state: &CardScheduleState,
    now_unix_ms: i64,
    ease: i32,
) -> f64 {
    // Again on a review card drops into relearning rather than scheduling an interval.
    if ease == 1 {
        return config.relearn_steps.first().copied().unwrap_or(600.0);
    }
    let days = fsrs_scheduled_days(config, state, ease, now_unix_ms)
        .unwrap_or_else(|| f64::from(config.graduating_interval_days));
    clamp_days(config, days) * 86_400.0
}

/// Run the FSRS-6 scheduler for one rating and return the resulting interval in days, or `None`
/// if the weights are unusable. `now_unix_ms` of 0 means "same instant as the card's last review",
/// which is what a not-yet-answered card needs.
fn fsrs_scheduled_days(
    config: &DeckScheduleConfig,
    state: &CardScheduleState,
    ease: i32,
    now_unix_ms: i64,
) -> Option<f64> {
    fsrs_outcome(config, state, ease, now_unix_ms)
        .map(|o| o.days)
        // A scheduler answer of 0 days means "still in learning", not a graduation interval: report
        // it as unavailable so callers fall back to the deck's configured graduating interval.
        .filter(|days| *days >= 1.0)
}

/// What FSRS-6 says answering with `ease` does to this card.
struct FsrsOutcome {
    /// Resulting interval in days; 0 means the card stays in learning.
    days: f64,
    /// Resulting memory state, which the next preview needs.
    stability: f64,
    difficulty: f64,
}

fn fsrs_outcome(
    config: &DeckScheduleConfig,
    state: &CardScheduleState,
    ease: i32,
    now_unix_ms: i64,
) -> Option<FsrsOutcome> {
    let params = fsrs_parameters(config)?;
    let fsrs = FSRS::new(params);
    let now =
        chrono::DateTime::<chrono::Utc>::from_timestamp_millis(now_unix_ms).unwrap_or_default();
    let last_review =
        now - chrono::Duration::milliseconds((state.elapsed_days * 86_400_000.0) as i64);

    // A card that has never been answered carries no memory state (Anki stores it on first answer).
    // Presenting zeros as a memory state would divide by a zero stability, so such a card is offered
    // to the scheduler as New and gets FSRS's initial values instead.
    let has_memory_state = state.stability > 0.0;
    let card = Card {
        due: now,
        stability: state.stability,
        difficulty: state.difficulty,
        elapsed_days: state.elapsed_days.round() as i64,
        scheduled_days: state.interval_days.round() as i64,
        reps: 0,
        lapses: 0,
        state: if has_memory_state { fsrs_state(state.queue) } else { State::New },
        last_review,
    };

    let rating = rating_for_ease(ease);
    let log = fsrs.repeat(card, now);
    // `days == 0` (still learning) is handled by callers via the day-1 filter, so the scheduler
    // state itself is not carried beyond the numbers the previews and state advance need.
    log.get(&rating).map(|info| FsrsOutcome {
        days: info.card.scheduled_days as f64,
        stability: info.card.stability,
        difficulty: info.card.difficulty,
    })
}

/// FSRS parameters from the deck's weights and desired retention, clamped to a usable range.
fn fsrs_parameters(config: &DeckScheduleConfig) -> Option<Parameters> {
    // FSRS-6 takes 19 weights; an older/short list would change the formula, so refuse it and let
    // the caller fall back to the configured intervals rather than emit wrong numbers.
    if config.fsrs_weights.len() != 19 {
        return None;
    }
    let w: [f64; 19] = config.fsrs_weights.clone().try_into().ok()?;
    let retention = if config.desired_retention > 0.0 { config.desired_retention } else { 0.9 };
    Some(Parameters {
        request_retention: retention.clamp(0.7, 0.99),
        maximum_interval: config.max_interval_days.max(1),
        w,
        ..Parameters::default()
    })
}

fn fsrs_state(queue: i32) -> State {
    match queue {
        1 => State::Learning,
        2 => State::Review,
        3 => State::Relearning,
        _ => State::New,
    }
}

fn rating_for_ease(ease: i32) -> Rating {
    match ease {
        1 => Rating::Again,
        2 => Rating::Hard,
        4 => Rating::Easy,
        _ => Rating::Good,
    }
}

/// SM-2 review branch, for decks without FSRS weights.
fn sm2_review_preview(config: &DeckScheduleConfig, state: &CardScheduleState, ease: i32) -> f64 {
    let interval = state.interval_days.max(1.0);
    let days = match ease {
        1 => {
            let lapsed = interval * config.lapse_multiplier;
            lapsed.max(f64::from(config.lapse_min_interval_days))
        }
        2 => interval * config.hard_multiplier,
        4 => interval * config.easy_bonus,
        _ => interval * config.interval_multiplier,
    };
    clamp_days(config, days) * 86_400.0
}

fn clamp_days(config: &DeckScheduleConfig, days: f64) -> f64 {
    days.max(1.0).min(f64::from(config.max_interval_days.max(1)))
}

/// The card's scheduler state after answering with `ease`, mirroring Anki's basic scheduler.
///
/// The session applies this to a card it is about to requeue so the requeued copy carries previews
/// for the state the card is actually in, instead of the labels fetched before the grade. FSRS
/// memory state is threaded through from the same scheduler call that produced the interval, so
/// consecutive previews are computed from the state Anki itself would hold.
#[uniffi::export]
pub fn advance_state(
    config: &DeckScheduleConfig,
    state: &CardScheduleState,
    ease: i32,
    now_unix_ms: i64,
) -> CardScheduleState {
    let steps = steps_for(config, state.queue);
    let last_index = steps.len().saturating_sub(1);
    let current = usize::try_from(state.learning_step.max(0)).unwrap_or(0).min(last_index);
    let outcome =
        if config.fsrs_enabled() { fsrs_outcome(config, state, ease, now_unix_ms) } else { None };

    // Fold the scheduler's new memory state into the card before adjusting its queue position.
    let with_memory = CardScheduleState {
        stability: outcome.as_ref().map_or(state.stability, |o| o.stability),
        difficulty: outcome.as_ref().map_or(state.difficulty, |o| o.difficulty),
        ..state.clone()
    };

    if state.queue == 2 {
        // Review card: Again drops into relearning (Anki keeps the review interval for the card's
        // return), everything else reschedules within the review queue.
        return if ease == 1 {
            CardScheduleState { queue: 3, learning_step: 0, ..with_memory }
        } else {
            CardScheduleState {
                queue: 2,
                learning_step: -1,
                interval_days: review_days(config, state, ease, outcome.as_ref()),
                ..with_memory
            }
        };
    }

    // New / learning / relearning card.
    match ease {
        1 => CardScheduleState { queue: state.queue.max(1), learning_step: 0, ..with_memory },
        2 => CardScheduleState { learning_step: current as i32, ..with_memory },
        4 => graduate(config, ease, with_memory, outcome.as_ref()),
        _ if current >= last_index => graduate(config, ease, with_memory, outcome.as_ref()),
        _ => CardScheduleState { learning_step: (current + 1) as i32, ..with_memory },
    }
}

/// The card leaves the learning queue for the review queue, graduating with the FSRS interval when
/// the scheduler gives one and the deck's configured interval otherwise.
fn graduate(
    config: &DeckScheduleConfig,
    ease: i32,
    state: CardScheduleState,
    outcome: Option<&FsrsOutcome>,
) -> CardScheduleState {
    let configured = f64::from(if ease == 4 {
        config.easy_interval_days
    } else {
        config.graduating_interval_days
    });
    let days = outcome.map(|o| o.days).filter(|d| *d >= 1.0).unwrap_or(configured);
    CardScheduleState {
        queue: 2,
        learning_step: -1,
        interval_days: clamp_days(config, days),
        ..state
    }
}

/// Review-queue interval for a graded card, in days.
fn review_days(
    config: &DeckScheduleConfig,
    state: &CardScheduleState,
    ease: i32,
    outcome: Option<&FsrsOutcome>,
) -> f64 {
    if let Some(days) = outcome.map(|o| o.days).filter(|d| *d >= 1.0) {
        return clamp_days(config, days);
    }
    clamp_days(config, sm2_review_preview(config, state, ease) / 86_400.0)
}

/// The step list that governs this card: relearning steps while relearning, learning steps otherwise.
fn steps_for(config: &DeckScheduleConfig, queue: i32) -> &Vec<f64> {
    if queue == 3 { &config.relearn_steps } else { &config.learn_steps }
}
