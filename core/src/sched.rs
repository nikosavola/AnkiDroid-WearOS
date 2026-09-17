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

/// Anki's SM-2 constants (rslib `scheduler/states/review.rs`).
const INITIAL_EASE_FACTOR: f64 = 2.5;
const MINIMUM_EASE_FACTOR: f64 = 1.3;
const EASE_FACTOR_AGAIN_DELTA: f64 = -0.2;
const EASE_FACTOR_HARD_DELTA: f64 = -0.15;
const EASE_FACTOR_EASY_DELTA: f64 = 0.15;
const SECS_PER_DAY: f64 = 86_400.0;
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
    /// The card's SM-2 ease factor (`sm2_factor` / 1000, e.g. 2.5). 0.0 means unknown, in which
    /// case Anki's initial 2.5 applies.
    pub ease_factor: f64,
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

/// Interval in seconds the given ease (1..=4) would schedule, per Anki's own rules.
fn preview_for_grade(
    config: &DeckScheduleConfig,
    state: &CardScheduleState,
    now_unix_ms: i64,
    ease: i32,
) -> f64 {
    let on_steps = matches!(state.queue, 0 | 1 | 3) || state.interval_days < 1.0;
    if on_steps {
        steps_preview(config, state, ease, now_unix_ms)
    } else {
        review_preview(config, state, ease, now_unix_ms)
    }
}

/// A card on the learning/relearning ladder (Anki `LearnState`/`RelearnState`): Again restarts the
/// ladder, Hard is Anki's hard delay, Good advances a step and graduates from the last one, Easy
/// graduates immediately.
fn steps_preview(
    config: &DeckScheduleConfig,
    state: &CardScheduleState,
    ease: i32,
    now_unix_ms: i64,
) -> f64 {
    let steps = steps_for(config, state.queue);
    let last_index = steps.len().saturating_sub(1);
    let current = usize::try_from(state.learning_step.max(0)).unwrap_or(0).min(last_index);

    match ease {
        1 => steps.first().copied().unwrap_or(60.0),
        2 => hard_delay_secs(steps, current),
        4 => graduation_secs(config, state, ease, now_unix_ms),
        _ => {
            if current >= last_index {
                graduation_secs(config, state, ease, now_unix_ms)
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
        || round_in_days((current_secs * 1.5).min(current_secs + SECS_PER_DAY)),
        |next| round_in_days(f64::midpoint(current_secs, next)),
    )
}

/// Anki rounds an interval to whole days once it reaches a day (`maybe_round_in_days`).
fn round_in_days(secs: f64) -> f64 {
    if secs < SECS_PER_DAY {
        return secs;
    }
    (secs / SECS_PER_DAY).round() * SECS_PER_DAY
}

/// What FSRS-6 says answering with `ease` does to this card.
struct FsrsOutcome {
    /// Resulting interval in days; 0 means the card stays in learning.
    days: f64,
    /// Resulting memory state, which the next preview needs.
    stability: f64,
    difficulty: f64,
}

/// The FSRS interval in days for one button, or `None` when the scheduler has no day-or-longer
/// interval to give (the card stays in learning) or the weights are unusable.
fn fsrs_scheduled_days(
    config: &DeckScheduleConfig,
    state: &CardScheduleState,
    ease: i32,
    now_unix_ms: i64,
) -> Option<f64> {
    fsrs_outcome(config, state, ease, now_unix_ms).map(|o| o.days).filter(|days| *days >= 1.0)
}

/// Run the FSRS-6 scheduler for one rating and report what it does to the card.
///
/// `now_unix_ms` of 0 means "the card's own last-review instant", which is what a not-yet-answered
/// card needs (elapsed time for such a card is taken from its state).
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
        now - chrono::Duration::milliseconds((state.elapsed_days * SECS_PER_DAY * 1000.0) as i64);

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

/// Where a card graduates to when it leaves the step ladder.
///
/// Anki treats the two ladders differently: a *learning* card graduates to the configured (or
/// FSRS) graduating interval, while a *relearning* card returns to Anki's failing review interval
/// (the interval it had, reduced by the lapse multiplier) - plus a day for Easy, which Anki gives
/// `failing + 1`. FSRS overrides both with the interval it computes for that button.
fn graduation_secs(
    config: &DeckScheduleConfig,
    state: &CardScheduleState,
    ease: i32,
    now_unix_ms: i64,
) -> f64 {
    let fsrs_days = if config.fsrs_enabled() {
        fsrs_scheduled_days(config, state, ease, now_unix_ms)
    } else {
        None
    };
    let days = fsrs_days.unwrap_or_else(|| {
        if state.queue == 3 {
            // Relearning: back to the interval the lapse reduced the card to.
            let failing = failing_review_interval_days(config, state);
            if ease == 4 { failing + 1.0 } else { failing }
        } else if ease == 4 {
            f64::from(config.easy_interval_days)
        } else {
            f64::from(config.graduating_interval_days)
        }
    });
    clamp_days(config, days) * SECS_PER_DAY
}

/// A card in the review queue (Anki `ReviewState`).
fn review_preview(
    config: &DeckScheduleConfig,
    state: &CardScheduleState,
    ease: i32,
    now_unix_ms: i64,
) -> f64 {
    // Again drops the card into relearning, so the button shows the first relearning step. The
    // interval the card returns to afterwards is the lapse-reduced one, applied at graduation.
    if ease == 1 {
        return config.relearn_steps.first().copied().unwrap_or(600.0);
    }
    if config.fsrs_enabled() {
        let days = fsrs_scheduled_days(config, state, ease, now_unix_ms).unwrap_or(1.0);
        return clamp_days(config, days) * SECS_PER_DAY;
    }
    sm2_passing_intervals(config, state)[ease_slot(ease)] * SECS_PER_DAY
}

/// Anki's SM-2 intervals for Hard, Good and Easy, in days (rslib `review.rs`).
///
/// These are driven by the card's own ease factor and how late it is, not by a fixed multiplier on
/// the interval: Good is `(interval + days_late / 2) * ease`, Easy is
/// `(interval + days_late) * ease * easy_bonus`, and each button is floored above the previous one
/// so the order can't invert. A card answered *ahead* of its due date takes Anki's separate early
/// branch, which schedules from the elapsed time instead of the interval.
fn sm2_passing_intervals(config: &DeckScheduleConfig, state: &CardScheduleState) -> [f64; 3] {
    let scheduled = state.interval_days.max(1.0);
    let ease = ease_factor(state);
    let days_late = state.elapsed_days - state.interval_days;
    let hard_multiplier = config.hard_multiplier;
    let easy_multiplier = config.easy_bonus;

    if days_late < 0.0 {
        let elapsed = state.elapsed_days.max(0.0);
        let half_usual = hard_multiplier / 2.0;
        let hard = constrain_passing_interval(
            config,
            (elapsed * hard_multiplier).max(scheduled * half_usual),
            0.0,
        );
        let good = constrain_passing_interval(config, (elapsed * ease).max(scheduled), 0.0);
        let reduced_bonus = easy_multiplier - (easy_multiplier - 1.0) / 2.0;
        let easy_interval = constrain_passing_interval(
            config,
            (elapsed * ease).max(scheduled) * reduced_bonus,
            0.0,
        );
        return [hard, good, easy_interval];
    }

    let hard_minimum = if hard_multiplier <= 1.0 { 0.0 } else { state.interval_days + 1.0 };
    let hard = constrain_passing_interval(config, scheduled * hard_multiplier, hard_minimum);
    let good_minimum = if hard_multiplier <= 1.0 { state.interval_days + 1.0 } else { hard + 1.0 };
    let good =
        constrain_passing_interval(config, (scheduled + days_late / 2.0) * ease, good_minimum);
    let easy_interval = constrain_passing_interval(
        config,
        (scheduled + days_late) * ease * easy_multiplier,
        good + 1.0,
    );
    [hard, good, easy_interval]
}

/// Anki's `constrain_passing_interval`: the deck's interval modifier, then rounding into the deck's
/// bounds.
///
/// Anki fuzzes passing intervals (±5% once they reach 2.5 days) from a card-and-day seeded PRNG;
/// our previews stay deterministic and unfuzzed, which is why a comparison against AnkiDroid's own
/// labels has to allow a few percent.
fn constrain_passing_interval(
    config: &DeckScheduleConfig,
    interval_days: f64,
    minimum: f64,
) -> f64 {
    let interval = interval_days * config.interval_multiplier;
    let (minimum, maximum) = review_interval_bounds(config, minimum);
    interval.round().clamp(minimum, maximum)
}

/// Anki's `min_and_max_review_intervals`: the deck's maximum interval, with the caller's floor
/// clamped into it.
fn review_interval_bounds(config: &DeckScheduleConfig, minimum: f64) -> (f64, f64) {
    let maximum = f64::from(config.max_interval_days.max(1));
    (minimum.clamp(1.0, maximum), maximum)
}

/// The card's SM-2 ease factor, or Anki's initial 2.5 when the card carries none.
fn ease_factor(state: &CardScheduleState) -> f64 {
    if state.ease_factor > 0.0 { state.ease_factor } else { INITIAL_EASE_FACTOR }
}

/// The interval a lapsed card returns to once it finishes relearning (Anki's
/// `failing_review_interval`): its old interval reduced by the lapse multiplier, never below the
/// deck's minimum lapse interval.
fn failing_review_interval_days(config: &DeckScheduleConfig, state: &CardScheduleState) -> f64 {
    let (minimum, maximum) =
        review_interval_bounds(config, f64::from(config.lapse_min_interval_days));
    (state.interval_days.max(1.0) * config.lapse_multiplier).clamp(minimum, maximum)
}

/// The card's SM-2 ease factor after each button, as Anki adjusts it.
fn ease_after(state: &CardScheduleState, ease: i32) -> f64 {
    let current = ease_factor(state);
    match ease {
        1 => (current + EASE_FACTOR_AGAIN_DELTA).max(MINIMUM_EASE_FACTOR),
        2 => (current + EASE_FACTOR_HARD_DELTA).max(MINIMUM_EASE_FACTOR),
        4 => current + EASE_FACTOR_EASY_DELTA,
        _ => current,
    }
}

/// Which of [`sm2_passing_intervals`]' three values a button selects.
fn ease_slot(ease: i32) -> usize {
    match ease {
        2 => 0,
        4 => 2,
        _ => 1,
    }
}

fn clamp_days(config: &DeckScheduleConfig, days: f64) -> f64 {
    days.max(1.0).min(f64::from(config.max_interval_days.max(1)))
}

/// The card's scheduler state after answering with `ease`, mirroring Anki's basic scheduler.
///
/// The session applies this to a card it is about to requeue so the requeued copy carries previews
/// for the state the card is actually in, instead of the labels fetched before the grade. FSRS
/// memory state and the SM-2 ease factor are threaded through the same way Anki updates them, so
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
        // Review card: Again drops into relearning (the lapse also lowers the ease factor and the
        // reworked interval is applied when relearning finishes), everything else reschedules
        // within the review queue.
        return if ease == 1 {
            CardScheduleState {
                queue: 3,
                learning_step: 0,
                ease_factor: ease_after(state, ease),
                ..with_memory
            }
        } else {
            CardScheduleState {
                queue: 2,
                learning_step: -1,
                interval_days: review_days(config, state, ease, now_unix_ms, outcome.as_ref()),
                ease_factor: ease_after(state, ease),
                ..with_memory
            }
        };
    }

    // New / learning / relearning card.
    match ease {
        1 => CardScheduleState {
            queue: state.queue.max(1),
            learning_step: 0,
            ease_factor: ease_after(state, ease),
            ..with_memory
        },
        2 => CardScheduleState {
            learning_step: current as i32,
            ease_factor: ease_after(state, ease),
            ..with_memory
        },
        4 => graduate(config, state, ease, with_memory, outcome.as_ref(), now_unix_ms),
        _ if current >= last_index => {
            graduate(config, state, ease, with_memory, outcome.as_ref(), now_unix_ms)
        }
        _ => CardScheduleState { learning_step: (current + 1) as i32, ..with_memory },
    }
}

/// The card leaves its step ladder for the review queue.
///
/// A card that was *learning* starts review life on Anki's initial ease factor; a card that was
/// *relearning* keeps the ease it already had (the lapse already reduced it), and returns to the
/// lapse-reduced interval rather than the graduating one.
fn graduate(
    config: &DeckScheduleConfig,
    state: &CardScheduleState,
    ease: i32,
    mut graduated: CardScheduleState,
    outcome: Option<&FsrsOutcome>,
    now_unix_ms: i64,
) -> CardScheduleState {
    let days = match outcome.map(|o| o.days).filter(|d| *d >= 1.0) {
        Some(days) => days,
        None if state.queue == 3 => {
            let failing = failing_review_interval_days(config, state);
            if ease == 4 { failing + 1.0 } else { failing }
        }
        None if ease == 4 => f64::from(config.easy_interval_days),
        None => f64::from(config.graduating_interval_days),
    };
    graduated.queue = 2;
    graduated.learning_step = -1;
    graduated.interval_days = clamp_days(config, days);
    if state.queue != 3 {
        graduated.ease_factor = INITIAL_EASE_FACTOR;
    }
    let _ = now_unix_ms;
    graduated
}

/// Review-queue interval for a graded card, in days, from FSRS when the deck uses it and Anki's
/// SM-2 rule otherwise.
fn review_days(
    config: &DeckScheduleConfig,
    state: &CardScheduleState,
    ease: i32,
    now_unix_ms: i64,
    outcome: Option<&FsrsOutcome>,
) -> f64 {
    if let Some(days) = outcome.map(|o| o.days).filter(|d| *d >= 1.0) {
        return clamp_days(config, days);
    }
    if config.fsrs_enabled() {
        return clamp_days(
            config,
            fsrs_scheduled_days(config, state, ease, now_unix_ms).unwrap_or(1.0),
        );
    }
    clamp_days(config, sm2_passing_intervals(config, state)[ease_slot(ease)])
}

/// The step list that governs this card: relearning steps while relearning, learning steps otherwise.
fn steps_for(config: &DeckScheduleConfig, queue: i32) -> &Vec<f64> {
    if queue == 3 { &config.relearn_steps } else { &config.learn_steps }
}
