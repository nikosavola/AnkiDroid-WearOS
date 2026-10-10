//! The multi-day card pool: today's queue, daily limits, reconciliation, and pool-backed
//! session grading.

use super::{cleanup_db, db_test_guard, sm2_deck_config};
use crate::*;

// ---- Multi-day card pool (pool.rs) ----

/// 2026-10-07 05:00 UTC, which is Anki day 20733 with the default 4am rollover at UTC offset 0.
const POOL_NOW_SECS: i64 = 1_791_349_200;
const POOL_NOW_MS: i64 = POOL_NOW_SECS * 1_000;
const POOL_DAY: i64 = 20_733;

fn pool_card_state(queue: i32) -> CardScheduleState {
    CardScheduleState { queue, ..Default::default() }
}

fn pool_card_full(
    id: i64,
    front: &str,
    due_day: Option<i64>,
    due_at_secs: Option<i64>,
    state: Option<CardScheduleState>,
) -> WearosCard {
    WearosCard {
        id,
        note_id: id * 10,
        ord: 0,
        front: front.to_string(),
        back: "A".to_string(),
        button_count: 4,
        next_review_times: vec![
            "1m".to_string(),
            "10m".to_string(),
            "4d".to_string(),
            "7d".to_string(),
        ],
        due_day,
        due_at_secs,
        state,
        queued_day: None,
    }
}

fn pool_batch(deck_id: i64, name: &str, cards: Vec<WearosCard>) -> WearosDueBatch {
    WearosDueBatch {
        deck_id,
        deck_name: name.to_string(),
        cards,
        rollover_hour: DEFAULT_ROLLOVER_HOUR,
        fetched_at_ms: POOL_NOW_MS,
        schedule_config: None,
        queue_day: None,
    }
}

/// A fresh, empty DB on a unique path for a pool test.
fn pool_db(name: &str) -> String {
    let path = std::env::temp_dir()
        .join(format!("test_wearos_pool_{name}.db"))
        .to_string_lossy()
        .to_string();
    cleanup_db(&path);
    init_database(path.clone()).unwrap();
    path
}

#[test]
fn storing_a_pool_builds_todays_queue_in_learning_review_new_order() {
    let _guard = db_test_guard();
    let path = pool_db("queue_order");

    let learning = pool_card_full(1, "L", None, Some(POOL_NOW_SECS - 60), Some(pool_card_state(1)));
    let review = pool_card_full(2, "R", Some(POOL_DAY), None, Some(pool_card_state(2)));
    let new_card = pool_card_full(3, "N", None, None, Some(pool_card_state(0)));
    let tomorrow = pool_card_full(4, "T", Some(POOL_DAY + 1), None, Some(pool_card_state(2)));
    pool_store_batch(pool_batch(1, "Deck", vec![learning, review, new_card, tomorrow])).unwrap();

    let batch = pool_today_batch(1, POOL_NOW_MS, 0).unwrap();
    assert_eq!(batch.deck_name, "Deck");
    let ids: Vec<i64> = batch.cards.iter().map(|c| c.id).collect();
    assert_eq!(ids, vec![1, 2, 3], "learning, then review, then new; tomorrow's card waits");

    // Past tomorrow's rollover the pooled card joins the queue as a due review.
    let next = pool_today_batch(1, POOL_NOW_MS + 86_400_000, 0).unwrap();
    assert!(next.cards.iter().any(|c| c.id == 4), "the card due tomorrow is included the next day");

    cleanup_db(&path);
}

#[test]
fn a_learning_card_due_in_thirty_minutes_is_not_yet_in_todays_queue() {
    let _guard = db_test_guard();
    let path = pool_db("learn_ahead");

    let soon = pool_card_full(1, "L", None, Some(POOL_NOW_SECS + 1_800), Some(pool_card_state(1)));
    pool_store_batch(pool_batch(7, "Deck", vec![soon])).unwrap();

    let batch = pool_today_batch(7, POOL_NOW_MS, 0).unwrap();
    assert!(batch.cards.is_empty(), "30 minutes is past the 20-minute learn-ahead window");

    let summaries = pool_deck_summaries(POOL_NOW_MS, 0).unwrap();
    let summary = summaries.iter().find(|s| s.deck_id == 7).unwrap();
    assert_eq!(summary.due_now, 0);
    assert_eq!(summary.learning_later, 1, "the card is counted as due later today");
    assert_eq!(summary.next_learning_due_secs, Some(POOL_NOW_SECS + 1_800));

    // Fifteen minutes later the card falls inside the window and joins today's queue.
    let batch = pool_today_batch(7, POOL_NOW_MS + 15 * 60 * 1_000, 0).unwrap();
    assert_eq!(batch.cards.len(), 1);
    assert_eq!(batch.cards[0].id, 1);

    cleanup_db(&path);
}

#[test]
fn daily_limits_cap_reviews_and_new_cards_then_reset_next_day() {
    let _guard = db_test_guard();
    let path = pool_db("limits");

    let config = parse_deck_schedule_config(
        serde_json::json!({ "new": { "perDay": 1 }, "rev": { "perDay": 2 } }).to_string(),
    )
    .unwrap();
    let mut cards = Vec::new();
    for id in 1..=3 {
        cards.push(pool_card_full(id, "R", Some(POOL_DAY), None, Some(pool_card_state(2))));
    }
    for id in 4..=5 {
        cards.push(pool_card_full(id, "N", None, None, Some(pool_card_state(0))));
    }
    let batch = WearosDueBatch {
        deck_id: 9,
        deck_name: "Deck".to_string(),
        cards,
        rollover_hour: DEFAULT_ROLLOVER_HOUR,
        fetched_at_ms: POOL_NOW_MS,
        schedule_config: Some(config),
        queue_day: None,
    };
    pool_store_batch(batch).unwrap();

    let today = pool_today_batch(9, POOL_NOW_MS, 0).unwrap();
    let count_queue = |batch: &WearosDueBatch, queue: i32| {
        batch.cards.iter().filter(|c| c.state.as_ref().is_some_and(|s| s.queue == queue)).count()
    };
    assert_eq!(count_queue(&today, 2), 2, "reviews_per_day caps the review bucket");
    assert_eq!(count_queue(&today, 0), 1, "new_per_day caps the new bucket");

    // Two review grades and one new grade for today exhaust both limits.
    pool_log_grade(9, 1, POOL_DAY, 2).unwrap();
    pool_log_grade(9, 2, POOL_DAY, 2).unwrap();
    pool_log_grade(9, 4, POOL_DAY, 0).unwrap();
    let drained = pool_today_batch(9, POOL_NOW_MS, 0).unwrap();
    assert_eq!(count_queue(&drained, 2), 0, "spent reviews stay spent for the day");
    assert_eq!(count_queue(&drained, 0), 0, "spent new cards stay spent for the day");

    // The next Anki day resets the counts.
    let next = pool_today_batch(9, POOL_NOW_MS + 86_400_000, 0).unwrap();
    assert_eq!(count_queue(&next, 2), 2);
    assert_eq!(count_queue(&next, 0), 1);

    cleanup_db(&path);
}

#[test]
fn a_today_batch_learning_card_leads_the_queue_outside_the_review_limit() {
    let _guard = db_test_guard();
    let path = pool_db("today_learning");

    let config =
        parse_deck_schedule_config(serde_json::json!({ "rev": { "perDay": 1 } }).to_string())
            .unwrap();
    // Today's batch ships both without a due day or time; only the queue tells them apart.
    let review = pool_card_full(1, "R", None, None, Some(pool_card_state(2)));
    let learning = pool_card_full(2, "L", None, None, Some(pool_card_state(1)));
    let batch = WearosDueBatch {
        schedule_config: Some(config),
        ..pool_batch(11, "Deck", vec![review, learning])
    };
    pool_store_batch(batch).unwrap();

    let ids: Vec<i64> =
        pool_today_batch(11, POOL_NOW_MS, 0).unwrap().cards.iter().map(|c| c.id).collect();
    assert_eq!(ids, vec![2, 1], "the learning card comes first and the review still fits");

    cleanup_db(&path);
}

fn queue_ids(deck_id: i64, now_ms: i64) -> Vec<i64> {
    pool_today_batch(deck_id, now_ms, 0).unwrap().cards.iter().map(|c| c.id).collect()
}

#[test]
fn offline_days_spread_new_cards_through_reviews_per_the_decks_new_mix() {
    let _guard = db_test_guard();
    let path = pool_db("new_mix");

    let cards = |deck: i64| {
        let mut cards: Vec<WearosCard> = (1..=4)
            .map(|i| {
                pool_card_full(deck * 100 + i, "R", Some(POOL_DAY), None, Some(pool_card_state(2)))
            })
            .collect();
        for i in 5..=6 {
            cards.push(pool_card_full(deck * 100 + i, "N", None, None, Some(pool_card_state(0))));
        }
        cards
    };
    let with_mix = |deck: i64, mix: Option<i32>| {
        let mut options = serde_json::json!({});
        if let Some(mix) = mix {
            options["newMix"] = mix.into();
        }
        let config = parse_deck_schedule_config(options.to_string()).unwrap();
        pool_store_batch(pool_config_batch(deck, config, cards(deck))).unwrap();
        queue_ids(deck, POOL_NOW_MS).iter().map(|id| id % 100).collect::<Vec<_>>()
    };

    // rslib's Intersperser over 4 reviews and 2 new cards.
    assert_eq!(with_mix(1, None), vec![1, 5, 2, 3, 6, 4], "Anki's default mixes them in");
    assert_eq!(with_mix(2, Some(1)), vec![1, 2, 3, 4, 5, 6], "new after reviews");
    assert_eq!(with_mix(3, Some(2)), vec![5, 6, 1, 2, 3, 4], "new before reviews");

    cleanup_db(&path);
}

#[test]
fn interday_learning_mixes_into_reviews_and_draws_on_the_review_limit() {
    let _guard = db_test_guard();
    let path = pool_db("day_learning");

    let config = parse_deck_schedule_config(
        serde_json::json!({ "rev": { "perDay": 3 }, "interdayLearningMix": 1 }).to_string(),
    )
    .unwrap();
    let mut cards: Vec<WearosCard> = (1..=3)
        .map(|id| pool_card_full(id, "R", Some(POOL_DAY), None, Some(pool_card_state(2))))
        .collect();
    cards.push(pool_card_full(4, "D", Some(POOL_DAY), None, Some(pool_card_state(3))));
    pool_store_batch(pool_config_batch(13, config, cards)).unwrap();

    assert_eq!(
        queue_ids(13, POOL_NOW_MS),
        vec![1, 2, 4],
        "the interday learning card is gathered first, takes a review slot, and goes after"
    );

    cleanup_db(&path);
}

#[test]
fn ankidroids_own_queue_keeps_its_order_and_limits_on_the_day_it_was_fetched() {
    let _guard = db_test_guard();
    let path = pool_db("anki_queue");

    // The preset says no new cards, but a deck-level override (invisible to the provider) let
    // AnkiDroid queue two of them today, mixed among its reviews.
    let config = pool_schedule_config(&[1.0, 10.0], 0, 200);
    let queued = |id: i64, queue: i32| WearosCard {
        queued_day: Some(POOL_DAY),
        ..pool_card_full(id, "Q", None, None, Some(pool_card_state(queue)))
    };
    let pooled_new = pool_card_full(4, "N", None, None, Some(pool_card_state(0)));
    let overdue = pool_card_full(5, "R", Some(POOL_DAY - 1), None, Some(pool_card_state(2)));
    let batch = WearosDueBatch {
        queue_day: Some(POOL_DAY),
        ..pool_config_batch(
            14,
            config,
            vec![queued(1, 0), queued(2, 2), queued(3, 0), pooled_new, overdue],
        )
    };
    pool_store_batch(batch).unwrap();

    assert_eq!(queue_ids(14, POOL_NOW_MS), vec![1, 2, 3], "AnkiDroid's order, nothing added");

    // A day later the untouched cards are ordinary overdue and new cards under the deck config.
    assert_eq!(queue_ids(14, POOL_NOW_MS + 86_400_000), vec![2, 5]);

    cleanup_db(&path);
}

#[test]
fn an_empty_ankidroid_queue_for_today_holds_back_pooled_new_cards_until_tomorrow() {
    let _guard = db_test_guard();
    let path = pool_db("anki_queue_empty");

    // The user finished the deck on the phone; the pool still ships new cards for later days.
    let config = pool_schedule_config(&[1.0, 10.0], 5, 200);
    let pooled_new = pool_card_full(1, "N", None, None, Some(pool_card_state(0)));
    let batch = WearosDueBatch {
        queue_day: Some(POOL_DAY),
        ..pool_config_batch(15, config, vec![pooled_new])
    };
    pool_store_batch(batch).unwrap();

    assert!(queue_ids(15, POOL_NOW_MS).is_empty(), "today's new cards were already studied");
    assert_eq!(queue_ids(15, POOL_NOW_MS + 86_400_000), vec![1]);

    cleanup_db(&path);
}

#[test]
fn a_partial_ankidroid_queue_leads_but_does_not_hold_back_the_pool() {
    let _guard = db_test_guard();
    let path = pool_db("anki_queue_partial");

    // The phone hit its fetch cap, so it marks the cards it got but not the day as complete.
    let config = pool_schedule_config(&[1.0, 10.0], 5, 200);
    let queued = WearosCard {
        queued_day: Some(POOL_DAY),
        ..pool_card_full(1, "Q", None, None, Some(pool_card_state(2)))
    };
    let pooled_new = pool_card_full(2, "N", None, None, Some(pool_card_state(0)));
    pool_store_batch(pool_config_batch(16, config, vec![pooled_new, queued])).unwrap();

    assert_eq!(queue_ids(16, POOL_NOW_MS), vec![1, 2], "AnkiDroid's cards first, pool after");

    cleanup_db(&path);
}

#[test]
fn a_pool_is_reconciled_after_write_back_only_once_it_has_aged() {
    let _guard = db_test_guard();
    let path = pool_db("reconcile");

    pool_store_batch(pool_batch(12, "Deck", vec![])).unwrap();
    assert!(!pool_needs_reconcile(12, POOL_NOW_MS + 60_000).unwrap(), "a fresh pool is kept");
    assert!(pool_needs_reconcile(12, POOL_NOW_MS + 30 * 60_000).unwrap());
    assert!(!pool_needs_reconcile(99, POOL_NOW_MS).unwrap(), "an unpooled deck is not fetched");

    cleanup_db(&path);
}

#[test]
fn restoring_a_batch_keeps_a_card_with_a_buffered_answer_and_replaces_the_rest() {
    let _guard = db_test_guard();
    let path = pool_db("restore");

    let x_old = pool_card_full(1, "old-x", None, None, Some(pool_card_state(2)));
    let y_old = pool_card_full(2, "old-y", None, None, Some(pool_card_state(2)));
    pool_store_batch(pool_batch(1, "Deck", vec![x_old, y_old])).unwrap();

    // Card 1 has a locally buffered grade, so the watch's copy is newer than the phone's.
    save_answer(WearosAnswer {
        id: "buffered".to_string(),
        note_id: 10,
        ord: 0,
        ease: 3,
        time_taken_ms: 1_000,
        deck_id: 1,
    })
    .unwrap();

    let x_new = pool_card_full(1, "new-x", None, None, Some(pool_card_state(2)));
    let y_new = pool_card_full(2, "new-y", None, None, Some(pool_card_state(2)));
    pool_store_batch(pool_batch(1, "Deck", vec![x_new, y_new])).unwrap();

    assert_eq!(
        pool_card(1).unwrap().unwrap().front,
        "old-x",
        "the buffered card keeps local state"
    );
    assert_eq!(pool_card(2).unwrap().unwrap().front, "new-y", "the un-buffered card is refreshed");

    cleanup_db(&path);
}

#[test]
fn pool_queue_keeps_shipped_labels_for_a_fresh_today_card_and_recomputes_otherwise() {
    let _guard = db_test_guard();
    let path = pool_db("labels");

    let config = sm2_deck_config();
    // A due_day card's shipped labels are stale, so they must be recomputed from config + state.
    let mut due_card = pool_card_full(
        1,
        "R",
        Some(POOL_DAY),
        None,
        Some(CardScheduleState {
            queue: 2,
            card_type: 2,
            interval_days: 10.0,
            ease_factor: 2.5,
            elapsed_days: 10.0,
            ..Default::default()
        }),
    );
    due_card.next_review_times = vec!["bogus".to_string()];
    let untouched = pool_card_full(2, "U", None, None, Some(pool_card_state(2)));

    let batch = WearosDueBatch {
        deck_id: 3,
        deck_name: "Deck".to_string(),
        cards: vec![due_card.clone(), untouched.clone()],
        rollover_hour: DEFAULT_ROLLOVER_HOUR,
        fetched_at_ms: POOL_NOW_MS,
        schedule_config: Some(config.clone()),
        queue_day: None,
    };
    pool_store_batch(batch).unwrap();

    let today = pool_today_batch(3, POOL_NOW_MS, 0).unwrap();
    let by_id = |batch: &WearosDueBatch, id: i64| {
        batch.cards.iter().find(|c| c.id == id).unwrap().next_review_times.clone()
    };
    let expected =
        preview_labels_for_buttons(&config, due_card.state.as_ref().unwrap(), POOL_NOW_MS, 4);
    assert_eq!(by_id(&today, 1), expected, "a due_day card is recomputed");
    assert_eq!(
        by_id(&today, 2),
        untouched.next_review_times,
        "an untouched card from today's fetched-today batch keeps the phone's labels"
    );

    // The next day the batch is stale, so even the untouched card is recomputed.
    let next_ms = POOL_NOW_MS + 86_400_000;
    let next = pool_today_batch(3, next_ms, 0).unwrap();
    let expected_next =
        preview_labels_for_buttons(&config, untouched.state.as_ref().unwrap(), next_ms, 4);
    assert_eq!(by_id(&next, 2), expected_next, "a stale batch's labels are recomputed");
    assert_ne!(by_id(&next, 2), untouched.next_review_times);

    cleanup_db(&path);
}

#[test]
fn pool_queue_refreshes_elapsed_days_from_the_last_review() {
    let _guard = db_test_guard();
    let path = pool_db("elapsed");

    let stale = CardScheduleState {
        queue: 2,
        card_type: 2,
        interval_days: 10.0,
        ease_factor: 2.5,
        elapsed_days: 999.0,
        last_review_secs: POOL_NOW_SECS - 3 * 86_400,
        ..Default::default()
    };
    let card = pool_card_full(1, "R", None, None, Some(stale));
    pool_store_batch(pool_batch(1, "Deck", vec![card])).unwrap();

    let batch = pool_today_batch(1, POOL_NOW_MS, 0).unwrap();
    let refreshed = batch.cards[0].state.as_ref().unwrap();
    assert!(
        (refreshed.elapsed_days - 3.0).abs() < f64::EPSILON,
        "elapsed days are recomputed at queue build: got {}",
        refreshed.elapsed_days
    );

    cleanup_db(&path);
}

// ---- Pool-backed review sessions (grading schedules offline) ----

/// A deck config with the given learning steps (minutes) and daily limits, no FSRS.
fn pool_schedule_config(
    learn_minutes: &[f64],
    new_per_day: i32,
    reviews_per_day: i32,
) -> DeckScheduleConfig {
    parse_deck_schedule_config(
        serde_json::json!({
            "new": { "delays": learn_minutes, "ints": [1, 4], "perDay": new_per_day },
            "rev": { "hardFactor": 1.2, "ivlFct": 1.0, "ease4": 1.3, "perDay": reviews_per_day },
            "lapse": { "delays": [10.0] }
        })
        .to_string(),
    )
    .unwrap()
}

fn pool_config_batch(
    deck_id: i64,
    config: DeckScheduleConfig,
    cards: Vec<WearosCard>,
) -> WearosDueBatch {
    WearosDueBatch {
        deck_id,
        deck_name: "Deck".to_string(),
        cards,
        rollover_hour: DEFAULT_ROLLOVER_HOUR,
        fetched_at_ms: POOL_NOW_MS,
        schedule_config: Some(config),
        queue_day: None,
    }
}

/// A review-queue card with a 10-day interval and ease 2.5, due on `due_day`.
fn pool_review_card(id: i64, due_day: i64) -> WearosCard {
    pool_card_full(
        id,
        "R",
        Some(due_day),
        None,
        Some(CardScheduleState {
            queue: 2,
            card_type: 2,
            interval_days: 10.0,
            ease_factor: 2.5,
            elapsed_days: 10.0,
            ..Default::default()
        }),
    )
}

/// The Anki day of the real clock, which `answer_card` reschedules against. The fixed POOL_*
/// constants would drift from it, so pool-grading tests use this and a real `now_ms`.
fn pool_real_today() -> (i64, i64) {
    let now_ms = crate::util::unix_millis() as i64;
    (now_ms, day_index(now_ms, 0, DEFAULT_ROLLOVER_HOUR))
}

#[test]
fn grading_a_review_card_good_in_a_pool_session_schedules_it_for_its_interval_and_logs_the_grade() {
    let _guard = db_test_guard();
    let path = pool_db("pool_grade_review");
    let (now_ms, today) = pool_real_today();

    // reviews_per_day = 1 so a second card stays held back until a grade is logged.
    let config = pool_schedule_config(&[1.0, 10.0], 20, 1);
    let cards = vec![pool_review_card(1, today), pool_review_card(2, today)];
    pool_store_batch(pool_config_batch(1, config, cards)).unwrap();

    let session = ReviewSession::from_pool(1, now_ms, 0).unwrap();
    assert_eq!(session.get_total_cards(), 1, "the daily review limit caps today's queue");
    session.answer_card(3, 1_000).unwrap();
    assert_eq!(
        session.get_progress_string(),
        "1/1",
        "Good schedules a day+ interval, so it graduates"
    );

    let snap = pool_card_snapshot(1).unwrap().unwrap();
    assert_eq!(
        snap.due_day,
        Some(today + 25),
        "Good on a 10-day review card schedules 25 days out"
    );
    assert!(snap.due_at_secs.is_none());
    assert!(snap.graded, "a locally scheduled card is marked graded");

    // The graded card left the pool for a future day, and the review grade spent the daily limit.
    let after = pool_today_batch(1, now_ms, 0).unwrap();
    assert!(after.cards.is_empty(), "the graded card is gone and the review limit is spent");

    cleanup_db(&path);
}

#[test]
fn grading_a_new_card_again_in_a_pool_session_requeues_it_at_its_first_step() {
    let _guard = db_test_guard();
    let path = pool_db("pool_grade_new_again");
    let (now_ms, _) = pool_real_today();

    let config = pool_schedule_config(&[1.0, 10.0], 20, 200);
    let new_card = pool_card_full(1, "N", None, None, Some(pool_card_state(0)));
    pool_store_batch(pool_config_batch(1, config, vec![new_card])).unwrap();

    let session = ReviewSession::from_pool(1, now_ms, 0).unwrap();
    let before = crate::util::unix_secs();
    session.answer_card(1, 1_000).unwrap();
    let after = crate::util::unix_secs();

    assert_eq!(session.get_progress_string(), "0/1", "Again requeues the card this session");
    assert_eq!(session.get_current_card().unwrap().id, 1);

    let snap = pool_card_snapshot(1).unwrap().unwrap();
    let due_at = snap.due_at_secs.unwrap();
    assert!(
        due_at >= before + 60 && due_at <= after + 60,
        "the first step is one minute out: got {due_at}"
    );
    assert!(snap.due_day.is_none());

    cleanup_db(&path);
}

#[test]
fn a_requeued_learning_card_not_yet_due_waits_behind_the_remaining_new_cards() {
    let _guard = db_test_guard();
    let path = pool_db("pool_learning_waits");
    let (now_ms, _) = pool_real_today();

    let config = pool_schedule_config(&[1.0, 10.0], 20, 200);
    let cards = vec![
        pool_card_full(1, "N1", None, None, Some(pool_card_state(0))),
        pool_card_full(2, "N2", None, None, Some(pool_card_state(0))),
    ];
    pool_store_batch(pool_config_batch(1, config, cards)).unwrap();

    let session = ReviewSession::from_pool(1, now_ms, 0).unwrap();
    session.answer_card(3, 1_000).unwrap();
    assert_eq!(
        session.get_current_card().unwrap().id,
        2,
        "card 1 is ten minutes out, so the other new card comes first"
    );

    session.answer_card(3, 1_000).unwrap();
    assert_eq!(
        session.get_current_card().unwrap().id,
        1,
        "with nothing else left, the earliest learning card is shown ahead of time"
    );

    cleanup_db(&path);
}

#[test]
fn undo_after_a_waiting_learning_card_gave_way_restores_the_graded_card() {
    let _guard = db_test_guard();
    let path = pool_db("pool_learning_waits_undo");
    let (now_ms, _) = pool_real_today();

    let config = pool_schedule_config(&[1.0, 10.0], 20, 200);
    let cards = vec![
        pool_card_full(1, "N1", None, None, Some(pool_card_state(0))),
        pool_card_full(2, "N2", None, None, Some(pool_card_state(0))),
    ];
    pool_store_batch(pool_config_batch(1, config, cards)).unwrap();

    let session = ReviewSession::from_pool(1, now_ms, 0).unwrap();
    session.answer_card(3, 1_000).unwrap();
    assert_eq!(session.undo_last_answer().unwrap().unwrap().id, 1);
    assert_eq!(session.remaining_batch().cards.len(), 2, "the requeued copy is gone again");

    cleanup_db(&path);
}

#[test]
fn a_learning_card_whose_next_step_is_an_hour_away_leaves_the_session_and_returns_within_the_learn_ahead_window()
 {
    let _guard = db_test_guard();
    let path = pool_db("pool_learning_hour");
    let (now_ms, _) = pool_real_today();

    let config = pool_schedule_config(&[1.0, 60.0], 20, 200);
    let learning = pool_card_full(
        1,
        "L",
        None,
        Some(now_ms.div_euclid(1_000) - 60),
        Some(CardScheduleState { queue: 1, card_type: 1, learning_step: 0, ..Default::default() }),
    );
    pool_store_batch(pool_config_batch(1, config, vec![learning])).unwrap();

    let session = ReviewSession::from_pool(1, now_ms, 0).unwrap();
    session.answer_card(3, 0).unwrap();
    assert_eq!(session.get_progress_string(), "1/1", "a step an hour out leaves the session");
    assert!(session.get_current_card().is_none());

    let due_at_ms = pool_card_snapshot(1).unwrap().unwrap().due_at_secs.unwrap() * 1_000;
    let window_ms = 20 * 60 * 1_000;
    let inside = pool_today_batch(1, due_at_ms - window_ms, 0).unwrap();
    assert!(inside.cards.iter().any(|c| c.id == 1), "due inside the learn-ahead window");
    let outside = pool_today_batch(1, due_at_ms - window_ms - 1, 0).unwrap();
    assert!(outside.cards.is_empty(), "a moment before the window it is not yet due");

    cleanup_db(&path);
}

#[test]
fn undo_in_a_pool_session_restores_the_stored_row_and_the_grade_log() {
    let _guard = db_test_guard();
    let path = pool_db("pool_undo");
    let (now_ms, today) = pool_real_today();

    let config = pool_schedule_config(&[1.0, 10.0], 20, 1);
    let cards = vec![pool_review_card(1, today), pool_review_card(2, today)];
    pool_store_batch(pool_config_batch(1, config, cards)).unwrap();

    let session = ReviewSession::from_pool(1, now_ms, 0).unwrap();
    session.answer_card(3, 0).unwrap();
    assert!(
        pool_today_batch(1, now_ms, 0).unwrap().cards.is_empty(),
        "the grade spent the day's review limit"
    );

    let back = session.undo_last_answer().unwrap().unwrap();
    assert_eq!(back.id, 1);
    assert_eq!(session.get_progress_string(), "0/1");

    let snap = pool_card_snapshot(1).unwrap().unwrap();
    assert_eq!(snap.due_day, Some(today), "the pre-grade due day is restored");
    assert!(!snap.graded, "the graded flag is restored");

    let after = pool_today_batch(1, now_ms, 0).unwrap();
    assert_eq!(after.cards.len(), 1);
    assert_eq!(after.cards[0].id, 1, "un-logging the grade frees the review limit again");

    cleanup_db(&path);
}

#[test]
fn a_pool_grade_stamps_last_review_on_the_stored_card_state() {
    let _guard = db_test_guard();
    let path = pool_db("pool_last_review");
    let (now_ms, today) = pool_real_today();

    let config = pool_schedule_config(&[1.0, 10.0], 20, 200);
    pool_store_batch(pool_config_batch(1, config, vec![pool_review_card(1, today)])).unwrap();

    let session = ReviewSession::from_pool(1, now_ms, 0).unwrap();
    let before = crate::util::unix_secs();
    session.answer_card(3, 0).unwrap();
    let after = crate::util::unix_secs();

    let state = pool_card_snapshot(1).unwrap().unwrap().card.state.unwrap();
    assert!(
        state.last_review_secs >= before && state.last_review_secs <= after,
        "the stored card is stamped with the review time: got {}",
        state.last_review_secs
    );
    assert!(state.elapsed_days.abs() < f64::EPSILON, "a just-graded card has no elapsed days");

    cleanup_db(&path);
}

#[test]
fn a_session_built_from_a_batch_leaves_the_card_pool_untouched() {
    let _guard = db_test_guard();
    let path = pool_db("non_pool_session");
    let (_, today) = pool_real_today();

    let card = pool_review_card(1, today);
    pool_store_batch(pool_batch(1, "Deck", vec![card.clone()])).unwrap();

    // A batch-built session has no pool context, so grading only touches the buffer.
    let session = ReviewSession::new(pool_batch(1, "Deck", vec![card]));
    session.answer_card(3, 0).unwrap();

    let snap = pool_card_snapshot(1).unwrap().unwrap();
    assert_eq!(snap.due_day, Some(today), "the stored row is untouched");
    assert!(!snap.graded, "a batch-built session never writes the pool");

    cleanup_db(&path);
}
