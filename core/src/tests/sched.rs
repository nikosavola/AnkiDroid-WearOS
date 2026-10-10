//! Offline interval previews: deck-config parsing, the learning ladder, FSRS-6 and SM-2 math.

use crate::*;

// ---- Offline interval previews (sched.rs) ----

/// A deck-config dict shaped like AnkiDroid's `FlashCardsContract.Deck.OPTIONS`: camelCase for
/// new/rev/lapse, snake_case for the FSRS keys (that mix is rslib's schema11, not a typo).
pub fn fsrs_deck_options() -> String {
    serde_json::json!({
        "new": { "delays": [1.0, 10.0], "ints": [1, 4] },
        "rev": { "ease4": 1.3, "ivlFct": 1.0, "maxIvl": 36500, "hardFactor": 1.2 },
        "lapse": { "delays": [10.0], "mult": 0.0, "minInt": 1 },
        "fsrs_params_6": vec![0.4072, 1.1829, 3.1262, 15.4722, 7.2102, 0.5316, 1.0651, 0.0234,
                              1.616, 0.1544, 1.0824, 1.9813, 0.0953, 0.2975, 2.2042, 0.2407,
                              2.9466, 0.5034, 0.6567],
        "desired_retention": 0.9
    })
    .to_string()
}

/// The same dict shape but with the stock FSRS-6 21 weights a real deck stores.
fn fsrs6_deck_options() -> String {
    serde_json::json!({
        "new": { "delays": [1.0, 10.0], "ints": [1, 4] },
        "rev": { "ease4": 1.3, "ivlFct": 1.0, "maxIvl": 36500, "hardFactor": 1.2 },
        "lapse": { "delays": [10.0], "mult": 0.0, "minInt": 1 },
        "fsrs_params_6": vec![0.212, 1.2931, 2.3065, 8.2956, 6.4133, 0.8334, 3.0194, 0.001,
                              1.8722, 0.1666, 0.796, 1.4835, 0.0614, 0.2629, 1.6483, 0.6014,
                              1.8729, 0.5425, 0.0912, 0.0658, 0.1542],
        "desired_retention": 0.9
    })
    .to_string()
}

/// The FSRS-6 weights and 0.85 retention from the collection that exposed the missing floors.
fn fsrs_floors_deck_options() -> String {
    serde_json::json!({
        "new": { "delays": [1.0, 10.0], "ints": [1, 4] },
        "rev": { "ease4": 1.3, "ivlFct": 1.0, "maxIvl": 36500, "hardFactor": 1.2 },
        "lapse": { "delays": [10.0], "mult": 0.0, "minInt": 1 },
        "fsrs_params_6": vec![0.055_251_993, 1.441_699, 12.829_912, 20.253_813, 6.677_253_7,
                              0.561_969_04, 3.171_826_1, 0.027_641_475, 1.688_151_8,
                              0.247_312_81, 0.629_756_15, 1.505_075_5, 0.036_390_96,
                              0.288_118_63, 1.706_024_4, 0.372_407_64, 1.816_298_8,
                              0.771_333_7, 0.018_401_38, 0.045_588_028, 0.212_830_99],
        "desired_retention": 0.85
    })
    .to_string()
}

/// Stock FSRS-6 default weights, as py-fsrs 6 ships them.
const W21: [f64; 21] = [
    0.212, 1.2931, 2.3065, 8.2956, 6.4133, 0.8334, 3.0194, 0.001, 1.8722, 0.1666, 0.796, 1.4835,
    0.0614, 0.2629, 1.6483, 0.6014, 1.8729, 0.5425, 0.0912, 0.0658, 0.1542,
];

/// FSRS-5 (19-weight) preset; the `fsrs` crate upgrades it to 21.
const W19: [f64; 19] = [
    0.40255, 1.18385, 3.173, 15.69105, 7.1949, 0.5345, 1.4604, 0.0046, 1.54575, 0.1192, 1.01925,
    1.9395, 0.11, 0.29605, 2.2698, 0.2315, 2.9898, 0.51655, 0.6621,
];

/// A minimal `OPTIONS` dict carrying just an FSRS weight list and desired retention, with a
/// single learning step so a Good grade graduates a learning card.
fn fsrs_options_with(weights: &[f64], desired_retention: f64) -> String {
    serde_json::json!({
        "new": { "delays": [1.0], "ints": [1, 4] },
        "fsrsParams6": weights,
        "desiredRetention": desired_retention,
    })
    .to_string()
}

/// The stability the `fsrs` crate reports after answering with `ease`.
fn assert_fsrs_stability(
    config: &DeckScheduleConfig,
    state: &CardScheduleState,
    ease: i32,
    expected: f64,
) {
    let actual = advance_state(config, state, ease, 0).stability;
    assert!((actual - expected).abs() < 1e-4, "ease {ease}: got {actual}, want {expected}");
}

/// A review card with a tiny FSRS stability; AnkiDroid floors its buttons to 1d / 2d / 3d.
fn tiny_stability_review_state() -> CardScheduleState {
    CardScheduleState {
        queue: 2,
        card_type: 2,
        interval_days: 1.0,
        learning_step: -1,
        ease_factor: 2.5,
        stability: 0.025,
        difficulty: 9.4,
        elapsed_days: 8.0,
        last_review_secs: 0,
        fuzz_seed: None,
    }
}

/// Preview intervals are floats; pedantic clippy forbids direct float equality.
pub fn assert_secs(actual: f64, expected: f64, msg: &str) {
    assert!((actual - expected).abs() < 0.5, "{msg}: got {actual}, want {expected}");
}

pub fn review_state() -> CardScheduleState {
    CardScheduleState {
        queue: 2,
        card_type: 2,
        interval_days: 10.0,
        learning_step: -1,
        ease_factor: 2.5,
        stability: 10.0,
        difficulty: 5.0,
        elapsed_days: 10.0,
        last_review_secs: 0,
        fuzz_seed: None,
    }
}

#[test]
fn test_parse_deck_options_reads_steps_ints_and_fsrs() {
    let config = parse_deck_schedule_config(fsrs_deck_options()).unwrap();

    // Delays arrive as minutes and are converted to seconds.
    assert_secs(config.learn_steps[0], 60.0, "first learning step");
    assert_secs(config.learn_steps[1], 600.0, "second learning step");
    assert_secs(config.relearn_steps[0], 600.0, "relearning step");
    assert_eq!(config.graduating_interval_days, 1);
    assert_eq!(config.easy_interval_days, 4);
    assert_eq!(config.max_interval_days, 36500);
    assert!(config.fsrs_enabled());
    assert_eq!(config.fsrs_weights.len(), 19);
    assert!((config.desired_retention - 0.9).abs() < f64::EPSILON);
}

#[test]
fn test_parse_deck_options_falls_back_to_defaults_for_missing_keys() {
    // An older AnkiDroid or a deck without overrides: every key absent, but parsing still works.
    let config = parse_deck_schedule_config("{}".to_string()).unwrap();

    assert_secs(config.learn_steps[0], 60.0, "default first learning step");
    assert_secs(config.learn_steps[1], 600.0, "default second learning step");
    assert_eq!(config.graduating_interval_days, 1);
    assert_eq!(config.easy_interval_days, 4);
    assert!(!config.fsrs_enabled(), "no weights means SM-2 scheduling");
    assert!((config.desired_retention - 0.9).abs() < f64::EPSILON);
}

#[test]
fn test_parse_deck_options_rejects_malformed_json() {
    let err = parse_deck_schedule_config("not json".to_string()).unwrap_err();
    assert!(matches!(err, CoreError::SerializationError { .. }));
}

#[test]
fn test_parse_deck_options_accepts_percentage_retention() {
    // Some surfaces express desired retention as 90 rather than 0.9.
    let json = serde_json::json!({ "desired_retention": 90.0 }).to_string();
    let config = parse_deck_schedule_config(json).unwrap();
    assert!((config.desired_retention - 0.9).abs() < 1e-9);
}

#[test]
fn test_parse_deck_options_reads_ankidroid_camel_case_fsrs_keys() {
    // The shape AnkiDroid 2.25.1 actually sends: empty v4/v5 params, camelCase v6 and retention.
    let json = serde_json::json!({
        "fsrsWeights": [],
        "fsrsParams5": [],
        "fsrsParams6": [0.0553, 1.4417, 12.8299, 20.2538, 6.6773, 0.562, 3.1718, 0.0276,
            1.6882, 0.2473, 0.6298, 1.5051, 0.0364, 0.2881, 1.706, 0.3724, 1.8163, 0.7713,
            0.0184, 0.0456, 0.2128],
        "desiredRetention": 0.85,
    })
    .to_string();
    let config = parse_deck_schedule_config(json).unwrap();
    assert_eq!(config.fsrs_weights.len(), 21);
    assert!(config.fsrs_enabled());
    assert!((config.desired_retention - 0.85).abs() < 1e-9);
}

#[test]
fn test_format_interval_label_matches_anki_units() {
    assert_eq!(format_interval_label(30.0), "30s");
    assert_eq!(format_interval_label(60.0), "1m");
    assert_eq!(format_interval_label(600.0), "10m");
    assert_eq!(format_interval_label(7200.0), "2h");
    assert_eq!(format_interval_label(345_600.0), "4d");
    // rslib timespan.rs: a month is 365/12 days, and hours/months/years keep one decimal.
    assert_eq!(format_interval_label(30.0 * 86_400.0), "30d");
    assert_eq!(format_interval_label(31.0 * 86_400.0), "1mo");
    assert_eq!(format_interval_label(5_400.0), "1.5h");
    assert_eq!(format_interval_label(45.0 * 86_400.0), "1.5mo");
    assert_eq!(format_interval_label(365.0 * 86_400.0), "1y");
}

#[test]
fn test_previews_walk_the_learning_ladder() {
    let config = parse_deck_schedule_config(fsrs_deck_options()).unwrap();
    let new_card =
        CardScheduleState { queue: 0, card_type: 0, learning_step: 0, ..Default::default() };
    // Again stays on the first step, Hard is the average of the first two steps (Anki's rule),
    // Good moves to the second step, and Easy graduates with the FSRS interval (15d for the
    // stock weights) rather than the SM-2 easy interval.
    assert_eq!(
        preview_labels(&config, &new_card, 0),
        vec!["1m", "6m", "10m", "15d"],
        "FSRS replaces the configured easy interval when the deck has weights"
    );

    // The same card after Good advanced it to the second step: Hard now repeats that step and
    // Good graduates, so its previews differ from the ones shipped with the batch.
    let second_step = CardScheduleState { queue: 1, learning_step: 1, ..Default::default() };
    let secs = preview_interval_secs(&config, &second_step, 0);
    assert_secs(secs[0], 60.0, "Again returns to the first step");
    assert_secs(secs[1], 600.0, "Hard repeats the current step");
    assert_secs(
        secs[2],
        3.0 * 86_400.0,
        "Good graduates to FSRS's initial stability for a card with no memory state yet",
    );
    assert!((secs[2] - secs[1]).abs() > 1.0, "graduating is not the learning step it replaced");
}

#[test]
fn test_hard_on_the_first_step_averages_the_first_two_steps() {
    // Anki's rule (rslib steps.rs): on the first step Hard is the average of the first two
    // steps, so it lands between Again and Good rather than repeating Again's delay.
    let config = sm2_deck_config();
    let first_step = CardScheduleState { queue: 1, learning_step: 0, ..Default::default() };
    let secs = preview_interval_secs(&config, &first_step, 0);
    assert_secs(secs[0], 60.0, "Again is the first step");
    assert_secs(secs[1], 330.0, "Hard is (1m + 10m) / 2");

    // On any later step it repeats that step instead.
    let second_step = CardScheduleState { queue: 1, learning_step: 1, ..Default::default() };
    let later = preview_interval_secs(&config, &second_step, 0);
    assert_secs(later[1], 600.0, "Hard repeats the second step");
}

#[test]
fn test_review_previews_use_fsrs_when_weights_present() {
    let config = parse_deck_schedule_config(fsrs_deck_options()).unwrap();
    let secs = preview_interval_secs(&config, &review_state(), 1_700_000_000_000);

    // FSRS-6 for S=10, D=5, elapsed 10d at 0.9 retention, fuzz off, so these are exact:
    // Hard 16d, Good 34d, Easy 81d.
    let days = |i: usize| (secs[i] / 86_400.0).round() as i64;
    assert_eq!(days(1), 16, "Hard");
    assert_eq!(days(2), 34, "Good");
    assert_eq!(days(3), 81, "Easy");
    assert_secs(secs[0], 600.0, "Again drops into the relearning step");
}

#[test]
fn test_fsrs6_weights_enable_fsrs_and_schedule_review_cards() {
    // A real FSRS-6 deck stores 21 weights; before this they were refused and every passing
    // button showed the 1d fallback.
    let config = parse_deck_schedule_config(fsrs6_deck_options()).unwrap();
    assert!(config.fsrs_enabled(), "21 FSRS-6 weights must enable FSRS");
    assert_eq!(config.fsrs_weights.len(), 21);

    let secs = preview_interval_secs(&config, &review_state(), 0);
    let days = |i: usize| (secs[i] / 86_400.0).round() as i64;
    assert_eq!(days(1), 23, "Hard");
    assert_eq!(days(2), 32, "Good");
    assert_eq!(days(3), 51, "Easy");
}

#[test]
fn test_fsrs_review_intervals_apply_ankis_floors() {
    // rslib's passing_fsrs_review_intervals floors each button so a longer-than-current FSRS
    // interval cannot round back below the card's own interval; on a tiny-stability card that
    // lifts every passing button to strict days rather than the flat 1d we used to show.
    let config = parse_deck_schedule_config(fsrs_floors_deck_options()).unwrap();
    let secs = preview_interval_secs(&config, &tiny_stability_review_state(), 0);

    assert_secs(secs[1], 86_400.0, "Hard is floored to one day");
    assert_secs(secs[2], 172_800.0, "Good is floored one day above Hard");
    assert_secs(secs[3], 259_200.0, "Easy is floored one day above Good");
    assert!(secs[1] < secs[2] && secs[2] < secs[3], "each passing button is strictly above");
}

#[test]
fn test_advance_state_uses_the_floored_fsrs_interval() {
    // The grading path must land on the same interval as the preview, not the raw FSRS value.
    let config = parse_deck_schedule_config(fsrs_floors_deck_options()).unwrap();
    let next = advance_state(&config, &tiny_stability_review_state(), 3, 0);
    assert!(
        (next.interval_days - 2.0).abs() < f64::EPSILON,
        "Good lands on the preview's 2d, got {}",
        next.interval_days
    );
}

#[test]
fn test_fsrs_easy_graduation_is_floored_above_good() {
    // rslib's answer_easy: Easy graduation is at least the rounded Good interval plus a day.
    let config = parse_deck_schedule_config(fsrs_floors_deck_options()).unwrap();
    let learning = CardScheduleState {
        queue: 1,
        card_type: 1,
        learning_step: 0,
        stability: 0.0203,
        difficulty: 9.2,
        elapsed_days: 1.0,
        ..Default::default()
    };
    let secs = preview_interval_secs(&config, &learning, 0);

    // Good graduates only from the last step, so read its graduation value from there.
    let last_step = CardScheduleState { learning_step: 1, ..learning };
    let good_graduation = preview_interval_secs(&config, &last_step, 0)[2];
    assert!(
        secs[3] >= good_graduation + 86_400.0,
        "Easy graduation ({}) must be at least Good graduation ({good_graduation}) plus a day",
        secs[3]
    );
    assert_secs(secs[3], 2.0 * 86_400.0, "Good rounds to 1d, so Easy is 2d");
}

#[test]
fn test_fsrs_floors_do_not_raise_a_shrinking_interval() {
    // greater_than_last returns 0 when the new interval is not larger than the current one, so
    // the floors must not inflate a card whose FSRS interval has shrunk.
    let config = parse_deck_schedule_config(fsrs_floors_deck_options()).unwrap();
    let state = CardScheduleState {
        queue: 2,
        card_type: 2,
        interval_days: 30.0,
        learning_step: -1,
        ease_factor: 2.5,
        stability: 2.0,
        difficulty: 5.0,
        elapsed_days: 1.0,
        last_review_secs: 0,
        fuzz_seed: None,
    };
    let secs = preview_interval_secs(&config, &state, 0);
    let days = |i: usize| (secs[i] / 86_400.0).round() as i64;
    assert!(days(1) < 30 && days(2) < 30 && days(3) < 30, "shrinking intervals stay shrunk");
}

#[test]
fn test_fsrs_initial_state_matches_reference() {
    // py-fsrs 6 reference: a card's first answer fixes (stability, difficulty) from its rating.
    let config = parse_deck_schedule_config(fsrs_options_with(&W21, 0.9)).unwrap();
    let new_card = CardScheduleState { queue: 0, card_type: 0, ..Default::default() };

    assert_fsrs_stability(&config, &new_card, 1, 0.212);
    assert_fsrs_stability(&config, &new_card, 2, 1.2931);
    assert_fsrs_stability(&config, &new_card, 3, 2.3065);
    assert_fsrs_stability(&config, &new_card, 4, 8.2956);

    let difficulty = |ease: i32| advance_state(&config, &new_card, ease, 0).difficulty;
    assert!((difficulty(1) - 6.4133).abs() < 1e-4);
    assert!((difficulty(2) - 5.112_171).abs() < 1e-4);
    assert!((difficulty(3) - 2.118_104).abs() < 1e-4);
    assert!((difficulty(4) - 1.0).abs() < 1e-4);
}

#[test]
fn test_fsrs_review_after_ten_days_matches_reference() {
    // S=10, D=5, elapsed 10d, R=0.9.
    let config = parse_deck_schedule_config(fsrs_options_with(&W21, 0.9)).unwrap();
    let state = review_state();
    assert_fsrs_stability(&config, &state, 1, 1.391_987);
    assert_fsrs_stability(&config, &state, 2, 23.246_875);
    assert_fsrs_stability(&config, &state, 3, 32.026_729);
    assert_fsrs_stability(&config, &state, 4, 51.253_862);

    let secs = preview_interval_secs(&config, &state, 0);
    let days = |i: usize| (secs[i] / 86_400.0).round() as i64;
    assert_eq!(days(1), 23, "Hard");
    assert_eq!(days(2), 32, "Good");
    assert_eq!(days(3), 51, "Easy");
}

#[test]
fn test_fsrs_review_after_thirteen_days_matches_reference() {
    // Elapsed 13d, R=0.880987.
    let config = parse_deck_schedule_config(fsrs_options_with(&W21, 0.9)).unwrap();
    let state = CardScheduleState { elapsed_days: 13.0, ..review_state() };
    assert_fsrs_stability(&config, &state, 2, 25.886_961);
    assert_fsrs_stability(&config, &state, 3, 36.416_629);
    assert_fsrs_stability(&config, &state, 4, 59.475_705);

    let secs = preview_interval_secs(&config, &state, 0);
    let days = |i: usize| (secs[i] / 86_400.0).round() as i64;
    assert_eq!(days(1), 26, "Hard");
    assert_eq!(days(2), 36, "Good");
    assert_eq!(days(3), 59, "Easy");
}

#[test]
fn test_fsrs_same_day_review_matches_reference() {
    // No elapsed time: the w[19] same-day term applies.
    let config = parse_deck_schedule_config(fsrs_options_with(&W21, 0.9)).unwrap();
    let state = CardScheduleState { elapsed_days: 0.0, ..review_state() };
    assert_fsrs_stability(&config, &state, 1, 3.051_249);
    assert_fsrs_stability(&config, &state, 2, 10.0);
    assert_fsrs_stability(&config, &state, 3, 10.0);
    assert_fsrs_stability(&config, &state, 4, 15.534_308);
}

#[test]
fn test_fsrs_next_interval_tracks_desired_retention() {
    // A learning card with S=10, D=5 whose Good grade lands back on stability 10, so its
    // graduation interval is `next_interval(10, retention)`.
    let graduated_good_days = |retention: f64| {
        let config = parse_deck_schedule_config(fsrs_options_with(&W21, retention)).unwrap();
        let learning = CardScheduleState {
            queue: 1,
            card_type: 1,
            learning_step: 0,
            stability: 10.0,
            difficulty: 5.0,
            elapsed_days: 0.0,
            ..Default::default()
        };
        (preview_interval_secs(&config, &learning, 0)[2] / 86_400.0).round() as i64
    };
    assert_eq!(graduated_good_days(0.9), 10);
    assert_eq!(graduated_good_days(0.85), 19);
    assert_eq!(graduated_good_days(0.95), 4);
}

#[test]
fn test_fsrs_filled_fsrs5_weights_match_reference() {
    // S=10, D=5, elapsed 10d, Good: the crate upgrades a 19-weight FSRS-5 preset to FSRS-6.
    let config = parse_deck_schedule_config(fsrs_options_with(&W19, 0.9)).unwrap();
    assert!(config.fsrs_enabled());
    let state = review_state();
    assert_fsrs_stability(&config, &state, 3, 32.954_264);
    assert_eq!(
        (preview_interval_secs(&config, &state, 0)[2] / 86_400.0).round() as i64,
        33,
        "Good graduates to the filled FSRS-5 interval"
    );
}

#[test]
fn test_fsrs_accepts_17_19_and_21_weight_presets() {
    for len in [17, 19, 21] {
        let weights: Vec<f64> = W21[..len].to_vec();
        let config = parse_deck_schedule_config(fsrs_options_with(&weights, 0.9)).unwrap();
        assert!(config.fsrs_enabled(), "{len} weights must leave FSRS on");
    }
}

#[test]
fn test_fsrs_unsupported_weight_count_leaves_fsrs_off() {
    // The crate (like Anki) only understands 17/19/21; anything else must not silently fall
    // back to the crate's default parameters.
    let config = parse_deck_schedule_config(fsrs_options_with(&[0.4, 1.2], 0.9)).unwrap();
    assert!(!config.fsrs_enabled());
}

#[test]
fn test_previews_are_unchanged_by_computing_fsrs_once_per_card() {
    // Computing all four ratings in one `next_states` call must give byte-for-byte the same
    // previews as the previous per-grade computation. These values were recorded from the
    // pre-refactor build and are exact, not rounded.
    let config = parse_deck_schedule_config(fsrs_options_with(&W21, 0.9)).unwrap();

    // Review card: Again the relearn step, then Hard 23d, Good 32d, Easy 51d.
    assert_eq!(
        preview_interval_secs(&config, &review_state(), 0),
        vec![600.0, 1_987_200.0, 2_764_800.0, 4_406_400.0],
    );

    // Learning card on its only step: Again 1m, Hard 1m30s, Good graduates 1d, Easy 2d.
    let learning = CardScheduleState {
        queue: 1,
        card_type: 1,
        learning_step: 0,
        stability: 0.0203,
        difficulty: 9.2,
        elapsed_days: 1.0,
        ..Default::default()
    };
    assert_eq!(preview_interval_secs(&config, &learning, 0), vec![60.0, 90.0, 86_400.0, 172_800.0],);
}

#[test]
fn test_advance_state_clears_elapsed_days_after_a_grade() {
    // The card was just reviewed, so its requeued copy must not compute the next preview as if
    // days had passed.
    let config = parse_deck_schedule_config(fsrs6_deck_options()).unwrap();
    let next = advance_state(&config, &review_state(), 3, 0);
    assert!(next.elapsed_days.abs() < f64::EPSILON, "elapsed_days must be cleared");
    assert_eq!(next.queue, 2, "a passing grade keeps the card in the review queue");
}

#[test]
fn test_review_previews_follow_ankis_sm2_math() {
    // Anki's review scheduling (rslib review.rs) is driven by the card's ease factor and how
    // late it is, not by a flat multiplier on the interval: for a 10-day card at ease 2.5 that
    // is Hard 12d, Good 25d, Easy 33d.
    let config = parse_deck_schedule_config(
        serde_json::json!({ "rev": { "hardFactor": 1.2, "ivlFct": 1.0, "ease4": 1.3 } })
            .to_string(),
    )
    .unwrap();
    assert!(!config.fsrs_enabled());
    let secs = preview_interval_secs(&config, &review_state(), 0);
    let days = |i: usize| (secs[i] / 86_400.0).round() as i64;

    assert_eq!(days(1), 12, "Hard is interval * hardFactor");
    assert_eq!(days(2), 25, "Good is (interval + daysLate/2) * ease");
    assert_eq!(days(3), 33, "Easy is (interval + daysLate) * ease * easyBonus");
    assert_secs(secs[0], 600.0, "Again shows the first relearning step, not an interval");
}

#[test]
fn test_sm2_late_review_grows_faster_and_stays_ordered() {
    let config = parse_deck_schedule_config(
        serde_json::json!({ "rev": { "hardFactor": 1.2, "ivlFct": 1.0, "ease4": 1.3 } })
            .to_string(),
    )
    .unwrap();
    let on_time = review_state();
    // Same card reviewed 20 days late: Anki adds half the lateness to Good, all of it to Easy.
    let late = CardScheduleState { elapsed_days: 30.0, ..review_state() };
    let days = |state: &CardScheduleState| {
        let secs = preview_interval_secs(&config, state, 0);
        [
            (secs[1] / 86_400.0).round() as i64,
            (secs[2] / 86_400.0).round() as i64,
            (secs[3] / 86_400.0).round() as i64,
        ]
    };
    let [hard, good, easy] = days(&on_time);
    let [late_hard, late_good, late_easy] = days(&late);
    assert_eq!(late_hard, hard, "Hard ignores lateness");
    assert!(late_good > good, "Good grows with lateness ({late_good} > {good})");
    assert!(late_easy > late_good, "Easy stays above Good ({late_easy} > {late_good})");
    assert!(hard < good && good < easy, "order never inverts");
}

#[test]
fn test_early_review_in_a_normal_deck_schedules_as_on_time() {
    // rslib's current.rs clamps a review card's due day to today in a normal deck, so its
    // elapsed days never fall below the interval and review.rs's early branch is not taken: a
    // card answered ahead of schedule gets the same buttons as one answered on time.
    let config = parse_deck_schedule_config(
        serde_json::json!({ "rev": { "hardFactor": 1.2, "ivlFct": 1.0, "ease4": 1.3 } })
            .to_string(),
    )
    .unwrap();
    let early = CardScheduleState { elapsed_days: 2.0, ..review_state() };
    let secs = preview_interval_secs(&config, &early, 0);
    let days = |i: usize| (secs[i] / 86_400.0).round() as i64;

    assert_eq!(preview_interval_secs(&config, &review_state(), 0), secs);
    assert_eq!((days(1), days(2), days(3)), (12, 25, 33));
}

#[test]
fn test_relearning_graduation_returns_to_the_interval_the_lapse_left() {
    // rslib cuts the interval when the card lapses (failing_review_interval) and stores the
    // cut value on the card, which is what the provider reports; graduation returns to it.
    let config = parse_deck_schedule_config(
        serde_json::json!({
            "new": { "delays": [1.0, 10.0], "ints": [1, 4] },
            "lapse": { "delays": [10.0], "mult": 0.5, "minInt": 1 }
        })
        .to_string(),
    )
    .unwrap();
    let review = CardScheduleState {
        queue: 2,
        card_type: 2,
        interval_days: 10.0,
        ease_factor: 2.5,
        elapsed_days: 10.0,
        ..Default::default()
    };

    let lapsed = advance_state(&config, &review, 1, 0);
    assert_eq!((lapsed.queue, lapsed.card_type), (1, 3), "relearning, in the learning queue");
    assert_eq!(lapsed.interval_days.round() as i64, 5, "10 days cut by the 0.5 multiplier");

    let secs = preview_interval_secs(&config, &lapsed, 0);
    let days = |i: usize| (secs[i] / 86_400.0).round() as i64;
    assert_secs(secs[0], 600.0, "Again repeats the relearning step");
    assert_eq!(days(2), 5, "Good returns to the cut interval, not cut again");
    assert_eq!(days(3), 6, "Easy is that interval plus a day");
}

#[test]
fn test_fuzz_reproduces_rslib_for_a_given_seed() {
    use rand::{Rng, SeedableRng};

    let config = sm2_deck_config();
    let review = |seed: Option<i64>| CardScheduleState {
        queue: 2,
        card_type: 2,
        interval_days: 100.0,
        ease_factor: 2.5,
        elapsed_days: 100.0,
        fuzz_seed: seed,
        ..Default::default()
    };
    let good_days =
        |seed| (preview_interval_secs(&config, &review(seed), 0)[2] / 86_400.0).round() as i64;

    // Unfuzzed Good is 100 * 2.5 = 250. rslib's delta there is 1 + 0.675 + 1.3 + 11.5 = 14.475
    // days, so the window is 236..=264.
    assert_eq!(good_days(None), 250);
    let fuzzed: Vec<i64> = (1..=40).map(|seed| good_days(Some(seed))).collect();
    assert!(fuzzed.iter().all(|d| (236..=264).contains(d)), "{fuzzed:?}");
    assert!(fuzzed.iter().any(|d| *d != 250), "a seed moves the interval");

    // The draw itself: rand 0.9's StdRng seeded as rslib seeds it.
    let factor: f32 = rand::rngs::StdRng::seed_from_u64(7).random_range(0.0..1.0);
    assert_eq!(good_days(Some(7)), 236 + (factor * 29.0).floor() as i64);
}

#[test]
fn test_grading_advances_the_fuzz_seed_by_one_rep() {
    let config = sm2_deck_config();
    let state = CardScheduleState {
        queue: 2,
        card_type: 2,
        interval_days: 10.0,
        ease_factor: 2.5,
        elapsed_days: 10.0,
        fuzz_seed: Some(1_000),
        ..Default::default()
    };
    assert_eq!(advance_state(&config, &state, 3, 0).fuzz_seed, Some(1_001));
}

#[test]
fn test_incomplete_fsrs_weights_fall_back_to_configured_intervals() {
    // A short weights list is a different formula generation; refuse it rather than emit
    // numbers that look right but aren't the collection's.
    let config = parse_deck_schedule_config(
        serde_json::json!({ "new": { "ints": [3, 7] }, "fsrs_params_6": [0.4, 1.2] }).to_string(),
    )
    .unwrap();
    assert!(!config.fsrs_enabled(), "an unsupported length means SM-2, not 1d previews");
    assert_eq!(config.fsrs_weights.len(), 2);

    let state = CardScheduleState { queue: 1, learning_step: 1, ..Default::default() };
    let secs = preview_interval_secs(&config, &state, 0);
    assert_secs(secs[2], 3.0 * 86_400.0, "the configured graduating interval is used");
}

#[test]
fn test_again_on_a_new_card_returns_to_the_first_step() {
    let config = parse_deck_schedule_config(fsrs_deck_options()).unwrap();
    // Answering Again resets the ladder, so the first step is shown again however far along.
    let state = CardScheduleState { queue: 1, learning_step: 2, ..Default::default() };
    let secs = preview_interval_secs(&config, &state, 0);
    assert_secs(secs[0], 60.0, "Again restarts at the first step");
}

/// A config with two learning steps and no FSRS weights, so the ladder is deterministic.
pub fn sm2_deck_config() -> DeckScheduleConfig {
    parse_deck_schedule_config(
        serde_json::json!({
            "new": { "delays": [1.0, 10.0], "ints": [1, 4] },
            "rev": { "hardFactor": 1.2, "ivlFct": 1.0, "ease4": 1.3 },
            "lapse": { "delays": [10.0] }
        })
        .to_string(),
    )
    .unwrap()
}

#[test]
fn test_parse_deck_options_reads_per_day_limits() {
    let config = parse_deck_schedule_config(
        serde_json::json!({ "new": { "perDay": 15 }, "rev": { "perDay": 150 } }).to_string(),
    )
    .unwrap();
    assert_eq!(config.new_per_day, 15);
    assert_eq!(config.reviews_per_day, 150);

    // Absent keys fall back to Anki's stock 20/200.
    let defaults = parse_deck_schedule_config("{}".to_string()).unwrap();
    assert_eq!(defaults.new_per_day, 20);
    assert_eq!(defaults.reviews_per_day, 200);
}
