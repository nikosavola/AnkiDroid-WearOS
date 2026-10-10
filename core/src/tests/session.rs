//! The review-session state machine: grading, requeueing, undo, and recomputed previews.

use super::{cleanup_db, db_test_guard, no_db_guard, sm2_deck_config};
use crate::*;

#[test]
fn test_review_session_state_machine() {
    // answer_card buffers into DB_CONN when a DB is configured; serialize with the buffer tests.
    let _guard = no_db_guard();
    let card1 = WearosCard {
        id: 1,
        note_id: 10,
        ord: 0,
        front: "Q1".to_string(),
        back: "A1".to_string(),
        button_count: 4,
        next_review_times: vec![
            "1m".to_string(),
            "10m".to_string(),
            "4d".to_string(),
            "7d".to_string(),
        ],
        due_day: None,
        due_at_secs: None,
        state: None,
        queued_day: None,
    };
    let card2 = WearosCard {
        id: 2,
        note_id: 20,
        ord: 1,
        front: "Q2".to_string(),
        back: "A2".to_string(),
        button_count: 3,
        next_review_times: vec!["1m".to_string(), "10m".to_string(), "4d".to_string()],
        due_day: None,
        due_at_secs: None,
        state: None,
        queued_day: None,
    };
    let batch = WearosDueBatch {
        deck_id: 123,
        deck_name: "Test".to_string(),
        cards: vec![card1, card2],
        rollover_hour: 4,
        fetched_at_ms: 0,
        schedule_config: None,
        queue_day: None,
    };

    let session = ReviewSession::new(batch);
    assert_eq!(session.get_total_cards(), 2);
    assert_eq!(session.get_progress_string(), "0/2");
    assert!(!session.is_revealed());

    let c1 = session.get_current_card().unwrap();
    assert_eq!(c1.front, "Q1");

    session.reveal_answer();
    assert!(session.is_revealed());

    // Good on card1 lands on "4d" (a day+ interval), so it graduates out of the session.
    let ans1 = session.answer_card(3, 5000).unwrap().unwrap();
    assert_eq!(ans1.ord, 0);
    assert_eq!(ans1.ease, 3);
    assert_eq!(ans1.time_taken_ms, 5000);
    assert_eq!(session.get_progress_string(), "1/2");
    assert!(!session.is_revealed());

    let c2 = session.get_current_card().unwrap();
    assert_eq!(c2.front, "Q2");

    // Easy on the 3-button card2 is "4d" -> graduates; queue now empty.
    let _ = session.answer_card(4, 2000).unwrap();
    assert_eq!(session.get_progress_string(), "2/2");
    assert!(session.get_current_card().is_none());
}

#[test]
fn test_review_session_undo() {
    let _guard = db_test_guard();
    let temp_dir = std::env::temp_dir();
    let db_path = temp_dir.join("test_wearos_undo.db").to_string_lossy().to_string();
    let _ = std::fs::remove_file(&db_path);
    init_database(db_path.clone()).unwrap();

    // Day+ intervals on Good so each grade graduates, keeping this test's queue math simple; the
    // requeue path is covered by test_undo_reverses_a_requeue.
    let mk = |id: i64, front: &str| WearosCard {
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
        due_day: None,
        due_at_secs: None,
        state: None,
        queued_day: None,
    };
    let batch = WearosDueBatch {
        deck_id: 1,
        deck_name: "T".to_string(),
        cards: vec![mk(1, "Q1"), mk(2, "Q2")],
        rollover_hour: 4,
        fetched_at_ms: 0,
        schedule_config: None,
        queue_day: None,
    };
    let session = ReviewSession::new(batch);

    assert!(!session.can_undo(), "nothing answered yet");
    assert!(session.undo_last_answer().unwrap().is_none());

    session.answer_card(3, 1000).unwrap(); // Good "4d" -> graduate c1
    session.answer_card(3, 2000).unwrap(); // Good "4d" -> graduate c2
    assert_eq!(session.get_progress_string(), "2/2");
    assert!(session.get_current_card().is_none());
    assert_eq!(get_buffered_answers().unwrap().len(), 2);

    // Undo once: back to card 2, its buffered answer removed.
    assert!(session.can_undo());
    let back = session.undo_last_answer().unwrap().unwrap();
    assert_eq!(back.front, "Q2");
    assert_eq!(session.get_progress_string(), "1/2");
    assert!(!session.is_revealed());
    assert_eq!(get_buffered_answers().unwrap().len(), 1);

    // Undo again: back to card 1, buffer empty.
    let back1 = session.undo_last_answer().unwrap().unwrap();
    assert_eq!(back1.front, "Q1");
    assert_eq!(session.get_progress_string(), "0/2");
    assert_eq!(get_buffered_answers().unwrap().len(), 0);
    assert!(!session.can_undo());

    let _ = std::fs::remove_file(&db_path);
}

/// A 4-button card whose learning-step buttons are sub-day and graduating buttons are day+.
fn learning_card(id: i64) -> WearosCard {
    WearosCard {
        id,
        note_id: id * 10,
        ord: 0,
        front: "Q".to_string(),
        back: "A".to_string(),
        button_count: 4,
        next_review_times: vec![
            "1m".to_string(),  // Again
            "10m".to_string(), // Hard
            "4d".to_string(),  // Good
            "7d".to_string(),  // Easy
        ],
        due_day: None,
        due_at_secs: None,
        state: None,
        queued_day: None,
    }
}

fn single_card_session(card: WearosCard) -> ReviewSession {
    ReviewSession::new(WearosDueBatch {
        deck_id: 1,
        deck_name: "T".to_string(),
        cards: vec![card],
        rollover_hour: 4,
        fetched_at_ms: 0,
        schedule_config: None,
        queue_day: None,
    })
}

#[test]
fn test_upcoming_card_ids_skip_the_current_card() {
    let _guard = no_db_guard();
    let session = ReviewSession::new(WearosDueBatch {
        deck_id: 1,
        deck_name: "T".to_string(),
        cards: vec![learning_card(1), learning_card(2), learning_card(3)],
        rollover_hour: 4,
        fetched_at_ms: 0,
        schedule_config: None,
        queue_day: None,
    });
    assert_eq!(session.upcoming_card_ids(1), vec![2]);
    assert_eq!(session.upcoming_card_ids(5), vec![2, 3]);
    assert_eq!(single_card_session(learning_card(9)).upcoming_card_ids(2), Vec::<i64>::new());
}

#[test]
fn test_progress_fraction_tracks_graduated_over_initial() {
    let _guard = no_db_guard();
    let session = ReviewSession::new(WearosDueBatch {
        deck_id: 1,
        deck_name: "T".to_string(),
        cards: vec![learning_card(1), learning_card(2), learning_card(3), learning_card(4)],
        rollover_hour: 4,
        fetched_at_ms: 0,
        schedule_config: None,
        queue_day: None,
    });
    assert!(session.get_progress_fraction().abs() < f32::EPSILON);

    // Good = "4d" (day+) graduates one of four cards -> 1/4.
    session.answer_card(3, 0).unwrap();
    assert!((session.get_progress_fraction() - 0.25).abs() < f32::EPSILON);

    // An empty session reports 0.0 rather than dividing by zero.
    let empty = ReviewSession::new(WearosDueBatch {
        deck_id: 1,
        deck_name: "T".to_string(),
        cards: vec![],
        rollover_hour: 4,
        fetched_at_ms: 0,
        schedule_config: None,
        queue_day: None,
    });
    assert!(empty.get_progress_fraction().abs() < f32::EPSILON);
}

#[test]
fn test_learning_step_requeues_until_graduated() {
    let _guard = no_db_guard();
    let session = single_card_session(learning_card(1));

    // Hard lands on "10m" (sub-day): the single card comes straight back, nothing graduates.
    session.answer_card(2, 1000).unwrap();
    assert_eq!(session.get_progress_string(), "0/1");
    assert_eq!(session.get_current_card().unwrap().front, "Q");

    // Good lands on "4d" (day+): graduates, session finishes.
    session.answer_card(3, 1000).unwrap();
    assert_eq!(session.get_progress_string(), "1/1");
    assert!(session.get_current_card().is_none());
}

#[test]
fn test_remaining_batch_drops_a_graduated_card() {
    let _guard = no_db_guard();
    let session = single_card_session(learning_card(1));

    // Good = "4d" (day+) graduates the card out of the queue.
    session.answer_card(3, 0).unwrap();

    let batch = session.remaining_batch();
    assert!(batch.cards.is_empty(), "a graduated card must not be re-presented on reopen");
}

#[test]
fn test_remaining_batch_keeps_a_requeued_card_at_the_back() {
    let _guard = no_db_guard();
    let session = ReviewSession::new(WearosDueBatch {
        deck_id: 1,
        deck_name: "T".to_string(),
        cards: vec![learning_card(1), learning_card(2)],
        rollover_hour: 4,
        fetched_at_ms: 0,
        schedule_config: None,
        queue_day: None,
    });

    // Again requeues card 1, so it comes back behind card 2.
    session.answer_card(1, 0).unwrap();

    let batch = session.remaining_batch();
    assert_eq!(batch.cards.len(), 2);
    assert_eq!(batch.cards[0].id, 2, "card 2 is now current");
    assert_eq!(batch.cards[1].id, 1, "the requeued card is at the back");
}

#[test]
fn test_remaining_batch_carries_deck_identity_and_config() {
    let _guard = no_db_guard();
    let session = ReviewSession::new(WearosDueBatch {
        deck_id: 42,
        deck_name: "Japani".to_string(),
        cards: vec![learning_card(1)],
        rollover_hour: 4,
        fetched_at_ms: 0,
        schedule_config: Some(sm2_deck_config()),
        queue_day: None,
    });

    let batch = session.remaining_batch();
    assert_eq!(batch.deck_id, 42);
    assert_eq!(batch.deck_name, "Japani");
    assert!(batch.schedule_config.is_some(), "the deck config must survive");
}
#[test]
fn test_interval_index_maps_ease_for_3_and_4_buttons() {
    let _guard = no_db_guard();

    // 4-button: Good is slot 2 ("4d"), so Good must graduate rather than read Hard's "10m".
    let s4 = single_card_session(learning_card(1));
    s4.answer_card(3, 0).unwrap();
    assert_eq!(s4.get_progress_string(), "1/1", "4-button Good must map to slot 2 (4d)");

    // 3-button (Again/Good/Easy): Good is slot 1 ("4d") -> graduate; Easy slot 2 ("10m").
    let three = WearosCard {
        id: 2,
        note_id: 2,
        ord: 0,
        front: "Q".to_string(),
        back: "A".to_string(),
        button_count: 3,
        next_review_times: vec!["1m".to_string(), "4d".to_string(), "10m".to_string()],
        due_day: None,
        due_at_secs: None,
        state: None,
        queued_day: None,
    };
    let s3 = single_card_session(three.clone());
    s3.answer_card(3, 0).unwrap();
    assert_eq!(s3.get_progress_string(), "1/1", "3-button Good must map to slot 1 (4d)");

    let s3b = single_card_session(three);
    s3b.answer_card(4, 0).unwrap();
    assert_eq!(s3b.get_progress_string(), "0/1", "3-button Easy must map to slot 2 (10m)");
    assert!(s3b.get_current_card().is_some());
}

#[test]
fn test_learning_requeue_cap_forces_graduation() {
    let _guard = no_db_guard();
    let session = single_card_session(learning_card(1));

    // The first few Hards ("10m") requeue; once the per-card cap is hit the card force-graduates
    // so the session can't loop forever offline.
    for _ in 0..3 {
        session.answer_card(2, 0).unwrap();
        assert!(session.get_current_card().is_some());
        assert_eq!(session.get_progress_string(), "0/1");
    }
    session.answer_card(2, 0).unwrap();
    assert!(session.get_current_card().is_none());
    assert_eq!(session.get_progress_string(), "1/1");
}

#[test]
fn test_again_requeues_without_hitting_the_cap() {
    let _guard = no_db_guard();
    let session = single_card_session(learning_card(1));

    // Again is a relearn step and may repeat freely: well past the cap, still not graduated.
    for _ in 0..6 {
        session.answer_card(1, 0).unwrap();
        assert!(session.get_current_card().is_some());
        assert_eq!(session.get_progress_string(), "0/1");
    }
}

#[test]
fn test_undo_reverses_a_requeue() {
    let _guard = db_test_guard();
    let temp_dir = std::env::temp_dir();
    let db_path = temp_dir.join("test_wearos_undo_requeue.db").to_string_lossy().to_string();
    let _ = std::fs::remove_file(&db_path);
    init_database(db_path.clone()).unwrap();

    let session = single_card_session(learning_card(1));

    // Hard requeues the card ("10m"): one buffered answer, still 0/1, card still present.
    session.answer_card(2, 1000).unwrap();
    assert_eq!(get_buffered_answers().unwrap().len(), 1);
    assert_eq!(session.get_progress_string(), "0/1");

    // Undo pulls the requeued copy back off the tail and drops the buffered answer.
    let back = session.undo_last_answer().unwrap().unwrap();
    assert_eq!(back.front, "Q");
    assert_eq!(session.get_progress_string(), "0/1");
    assert!(!session.can_undo());
    assert_eq!(get_buffered_answers().unwrap().len(), 0);

    let _ = std::fs::remove_file(&db_path);
}

#[test]
fn test_session_summary() {
    // answer_card buffers into DB_CONN when configured; serialize with the other DB tests.
    let _guard = no_db_guard();
    let mk = |id: i64| WearosCard {
        id,
        note_id: id,
        ord: 0,
        front: "Q".to_string(),
        back: "A".to_string(),
        button_count: 4,
        next_review_times: vec!["1m".to_string()],
        due_day: None,
        due_at_secs: None,
        state: None,
        queued_day: None,
    };
    let batch = WearosDueBatch {
        deck_id: 1,
        deck_name: "T".to_string(),
        cards: vec![mk(1), mk(2), mk(3)],
        rollover_hour: 4,
        fetched_at_ms: 0,
        schedule_config: None,
        queue_day: None,
    };
    let session = ReviewSession::new(batch);

    let empty = session.get_summary();
    assert_eq!(empty.total, 0);

    session.answer_card(1, 1000).unwrap(); // Again
    session.answer_card(3, 2000).unwrap(); // Good
    session.answer_card(4, 3000).unwrap(); // Easy
    let s = session.get_summary();
    assert_eq!(s.total, 3);
    assert_eq!(s.again, 1);
    assert_eq!(s.hard, 0);
    assert_eq!(s.good, 1);
    assert_eq!(s.easy, 1);
    assert_eq!(s.total_time_ms, 6000);

    // Undo drops the last (Easy) from the tally.
    session.undo_last_answer().unwrap();
    let s2 = session.get_summary();
    assert_eq!(s2.total, 2);
    assert_eq!(s2.easy, 0);
    assert_eq!(s2.total_time_ms, 3000);
}

#[test]
fn test_answer_card_fails_loudly_when_buffering_fails() {
    use std::os::unix::fs::PermissionsExt;
    // If the grade can't be persisted to the buffer, answer_card must surface the error and
    // leave the card current for a re-grade - not march on with a review that will never sync.
    let _guard = db_test_guard();
    let db_path = std::env::temp_dir().join("test_wearos_rofail.db").to_string_lossy().to_string();
    cleanup_db(&db_path);
    init_database(db_path.clone()).unwrap();

    // Make writes fail by write-protecting the DB file. If the process can still write (e.g.
    // running as root, where permissions don't bind), skip rather than assert a non-failure.
    let ro = std::fs::Permissions::from_mode(0o444);
    std::fs::set_permissions(&db_path, ro).unwrap();
    let probe = std::fs::OpenOptions::new().append(true).open(&db_path);
    if probe.is_ok() {
        cleanup_db(&db_path);
        return;
    }

    let session = single_card_session(learning_card(42));
    let result = session.answer_card(3, 1000);
    assert!(result.is_err(), "a grade whose buffering failed must return Err");
    assert_eq!(
        session.get_current_card().map(|c| c.id),
        Some(42),
        "the card must be restored to the front for a re-grade"
    );

    cleanup_db(&db_path);
}

// ---- Session integration: requeued cards carry recomputed previews ----

/// A learning card on its first step, as AnkiDroid ships it: shipped labels still say the old
/// ladder because they were fetched before any grade.
fn learning_card_with_state(id: i64) -> WearosCard {
    WearosCard {
        id,
        note_id: id * 10,
        ord: 0,
        front: "Q".to_string(),
        back: "A".to_string(),
        button_count: 4,
        next_review_times: vec![
            "1m".to_string(),
            "10m".to_string(),
            "10m".to_string(),
            "4d".to_string(),
        ],
        due_day: None,
        due_at_secs: None,
        state: Some(CardScheduleState { queue: 1, learning_step: 0, ..Default::default() }),
        queued_day: None,
    }
}

#[test]
fn test_requeued_card_gets_recomputed_previews() {
    let _guard = no_db_guard();
    let card = learning_card_with_state(1);
    let session = ReviewSession::new(WearosDueBatch {
        deck_id: 1,
        deck_name: "T".to_string(),
        cards: vec![card],
        rollover_hour: 4,
        fetched_at_ms: 0,
        schedule_config: Some(sm2_deck_config()),
        queue_day: None,
    });

    // Good on the first step advances the card to the second step, so it requeues (its shipped
    // Good label is a sub-day step).
    session.reveal_answer();
    session.answer_card(3, 1_000).unwrap();

    let requeued = session.get_current_card().expect("card requeued");
    assert_eq!(
        requeued.next_review_times,
        vec!["1m", "10m", "1d", "4d"],
        "previews are recomputed for the step the card is now on, not the stale shipped ones"
    );
    // The recomputed state is carried forward so a further grade stays consistent.
    assert_eq!(requeued.state.expect("state advanced").learning_step, 1);
}

#[test]
fn test_card_without_state_keeps_shipped_labels() {
    let _guard = no_db_guard();
    // Mock cards and older phone builds ship no per-card state: nothing to recompute from, so
    // AnkiDroid's own labels must survive the requeue untouched.
    let mut card = learning_card_with_state(1);
    card.state = None;
    let session = ReviewSession::new(WearosDueBatch {
        deck_id: 1,
        deck_name: "T".to_string(),
        cards: vec![card],
        rollover_hour: 4,
        fetched_at_ms: 0,
        schedule_config: Some(sm2_deck_config()),
        queue_day: None,
    });

    session.reveal_answer();
    session.answer_card(3, 1_000).unwrap();

    let requeued = session.get_current_card().expect("card requeued");
    assert_eq!(requeued.next_review_times, vec!["1m", "10m", "10m", "4d"]);
}

#[test]
fn test_session_without_schedule_config_keeps_shipped_labels() {
    let _guard = no_db_guard();
    let session = ReviewSession::new(WearosDueBatch {
        deck_id: 1,
        deck_name: "T".to_string(),
        cards: vec![learning_card_with_state(1)],
        rollover_hour: 4,
        fetched_at_ms: 0,
        schedule_config: None,
        queue_day: None,
    });

    session.reveal_answer();
    session.answer_card(3, 1_000).unwrap();

    let requeued = session.get_current_card().expect("card requeued");
    assert_eq!(requeued.next_review_times, vec!["1m", "10m", "10m", "4d"]);
}

#[test]
fn test_requeue_cap_follows_the_deck_step_count() {
    let _guard = no_db_guard();
    // A four-step ladder must not be cut short by the old fixed cap of 3: the card should
    // advance step by step (Good each time) until the last step, then graduate.
    let config = parse_deck_schedule_config(
        serde_json::json!({ "new": { "delays": [1.0, 10.0, 60.0, 240.0], "ints": [1, 4] } })
            .to_string(),
    )
    .unwrap();
    let mut card = learning_card_with_state(1);
    card.next_review_times =
        vec!["1m".to_string(), "10m".to_string(), "1h".to_string(), "4h".to_string()];
    let session = ReviewSession::new(WearosDueBatch {
        deck_id: 1,
        deck_name: "T".to_string(),
        cards: vec![card],
        rollover_hour: 4,
        fetched_at_ms: 0,
        schedule_config: Some(config),
        queue_day: None,
    });

    // Three Goods walk to the last step; the fourth graduates the card out of the session.
    for _ in 0..3 {
        session.reveal_answer();
        session.answer_card(3, 1_000).unwrap();
        assert!(session.get_current_card().is_some(), "still working through the ladder");
    }
    session.reveal_answer();
    session.answer_card(3, 1_000).unwrap();
    assert!(session.get_current_card().is_none(), "graduated after the last step");
}

#[test]
fn test_requeue_decision_uses_computed_interval_not_stale_label() {
    let _guard = no_db_guard();
    // The card is on the LAST step, but its shipped labels are the ones fetched while it was on
    // the first step ("10m" for Good, a learning step). Trusting those labels requeues the card;
    // Anki would graduate it, and the config we hold says so.
    let mut card = learning_card_with_state(1);
    card.state = Some(CardScheduleState { queue: 1, learning_step: 1, ..Default::default() });
    let session = ReviewSession::new(WearosDueBatch {
        deck_id: 1,
        deck_name: "T".to_string(),
        cards: vec![card],
        rollover_hour: 4,
        fetched_at_ms: 0,
        schedule_config: Some(sm2_deck_config()),
        queue_day: None,
    });

    session.reveal_answer();
    session.answer_card(3, 1_000).unwrap();

    assert!(
        session.get_current_card().is_none(),
        "Good from the last step graduates, so the session is done"
    );
}

#[test]
fn test_card_without_state_uses_the_shipped_label_for_the_requeue_decision() {
    let _guard = no_db_guard();
    // No per-card state (mock cards, older phone): fall back to the shipped label's unit, which
    // says Good is a sub-day step, so the card reappears.
    let mut card = learning_card_with_state(1);
    card.state = None;
    let session = ReviewSession::new(WearosDueBatch {
        deck_id: 1,
        deck_name: "T".to_string(),
        cards: vec![card],
        rollover_hour: 4,
        fetched_at_ms: 0,
        schedule_config: Some(sm2_deck_config()),
        queue_day: None,
    });

    session.reveal_answer();
    session.answer_card(3, 1_000).unwrap();

    assert!(session.get_current_card().is_some(), "label says sub-day, so it requeues");
}

#[test]
fn test_repeating_hard_terminates_the_session() {
    let _guard = no_db_guard();
    // Anki would let the user keep pressing Hard at the last step indefinitely; the watch bounds
    // the session instead, so a card repeating steps eventually graduates. This is a deliberate
    // deviation, and it is what keeps a review session finite.
    let mut card = learning_card_with_state(1);
    card.state = Some(CardScheduleState { queue: 1, learning_step: 1, ..Default::default() });
    let session = ReviewSession::new(WearosDueBatch {
        deck_id: 1,
        deck_name: "T".to_string(),
        cards: vec![card],
        rollover_hour: 4,
        fetched_at_ms: 0,
        schedule_config: Some(sm2_deck_config()),
        queue_day: None,
    });

    let cap = 2 + 1; // learn_steps.len() + 1
    for press in 1..=cap {
        session.reveal_answer();
        session.answer_card(2, 1_000).unwrap();
        assert!(
            session.get_current_card().is_some(),
            "Hard still requeues on press {press} of {cap}",
        );
    }
    session.reveal_answer();
    session.answer_card(2, 1_000).unwrap();
    assert!(session.get_current_card().is_none(), "the bound ends the session");
}

#[test]
fn test_preview_labels_follow_the_cards_button_count() {
    // AnkiDroid ships one label per button, so a recomputed card must keep that shape: a
    // 3-button card has no Hard, and the UI renders labels against its buttons positionally.
    let config = sm2_deck_config();
    let new_card = CardScheduleState { queue: 0, learning_step: 0, ..Default::default() };

    assert_eq!(
        preview_labels_for_buttons(&config, &new_card, 0, 4),
        vec!["1m", "6m", "10m", "4d"],
        "4-button cards keep Again/Hard/Good/Easy"
    );
    assert_eq!(
        preview_labels_for_buttons(&config, &new_card, 0, 3),
        vec!["1m", "10m", "4d"],
        "3-button cards drop the Hard slot"
    );
}

#[test]
fn test_just_due_review_is_not_an_early_review() {
    // Regression from on-device validation: a card reviewed 23h59m ago (0.9998 days elapsed) with
    // a 1-day interval was treated as an EARLY review, because its fractional elapsed time came
    // out just below the interval. Anki counts whole days, so this is an ordinary due card:
    // Hard 2d, Good 3d, Easy 4d - exactly what AnkiDroid reported.
    let config = sm2_deck_config();
    let just_due = CardScheduleState {
        queue: 2,
        card_type: 2,
        interval_days: 1.0,
        ease_factor: 2.5,
        elapsed_days: 0.9998,
        ..Default::default()
    };
    let secs = preview_interval_secs(&config, &just_due, 0);
    let days = |i: usize| (secs[i] / 86_400.0).round() as i64;

    assert_eq!(days(1), 2, "Hard is interval * hardFactor, floored above the interval");
    assert_eq!(days(2), 3, "Good is (interval + daysLate/2) * ease");
    assert_eq!(days(3), 4, "Easy at least a day above Good");
}
