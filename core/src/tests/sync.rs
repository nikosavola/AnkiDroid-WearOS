//! Wire-format round trips (zstd + proto) for due batches, answers, and decks.

use super::{assert_secs, fsrs_deck_options, review_state};
use crate::*;

#[test]
fn test_serialization_due_batch() {
    let card = WearosCard {
        id: 1,
        note_id: 2,
        ord: 0,
        front: "Q".to_string(),
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
        deck_id: 123,
        deck_name: "Test Deck".to_string(),
        cards: vec![card],
        rollover_hour: 4,
        fetched_at_ms: 0,
        schedule_config: None,
        queue_day: None,
    };

    let bytes = serialize_due_batch(batch).unwrap();
    let deserialized = deserialize_due_batch(bytes).unwrap();

    assert_eq!(deserialized.deck_id, 123);
    assert_eq!(deserialized.deck_name, "Test Deck");
    assert_eq!(deserialized.cards.len(), 1);
    assert_eq!(deserialized.cards[0].id, 1);
    assert_eq!(deserialized.cards[0].front, "Q");
}

fn card_with_front(front: &str) -> WearosCard {
    WearosCard {
        id: 1,
        note_id: 2,
        ord: 0,
        front: front.to_string(),
        back: "A".to_string(),
        button_count: 4,
        next_review_times: vec!["1m".to_string()],
        due_day: None,
        due_at_secs: None,
        state: None,
        queued_day: None,
    }
}

#[test]
fn test_due_batch_is_zstd_framed_and_smaller() {
    // A repetitive payload must come back zstd-framed and meaningfully smaller than raw JSON.
    let front = "<b>repeated content </b>".repeat(200);
    let json_len = serde_json::to_vec(&card_with_front(&front)).unwrap().len();
    let batch = WearosDueBatch {
        deck_id: 1,
        deck_name: "D".to_string(),
        cards: vec![card_with_front(&front)],
        rollover_hour: 4,
        fetched_at_ms: 0,
        schedule_config: None,
        queue_day: None,
    };
    let bytes = serialize_due_batch(batch).unwrap();
    // zstd frame magic number (little-endian 0xFD2FB528).
    assert_eq!(&bytes[..4], &[0x28, 0xb5, 0x2f, 0xfd], "payload is not zstd-framed");
    assert!(
        bytes.len() < json_len,
        "zstd+proto did not shrink the payload: {} vs {json_len}",
        bytes.len()
    );
}

#[test]
fn test_due_batch_round_trips_via_zstd_proto() {
    let batch = WearosDueBatch {
        deck_id: 55,
        deck_name: "文法".to_string(),
        cards: vec![card_with_front("<b>Q</b>"), card_with_front("Q2")],
        rollover_hour: 4,
        fetched_at_ms: 0,
        schedule_config: None,
        queue_day: None,
    };
    let restored = deserialize_due_batch(serialize_due_batch(batch).unwrap()).unwrap();
    assert_eq!(restored.deck_id, 55);
    assert_eq!(restored.deck_name, "文法");
    assert_eq!(restored.cards.len(), 2);
    assert_eq!(restored.cards[0].front, "<b>Q</b>");
    assert_eq!(restored.cards[1].front, "Q2");
    assert_eq!(restored.cards[0].next_review_times, vec!["1m".to_string()]);
}

#[test]
fn test_deserialize_due_batch_rejects_garbage() {
    // Not a valid zstd frame: a clear SerializationError, not a panic. (WearosDueBatch isn't
    // Debug, so match the result rather than unwrap_err.)
    let result = deserialize_due_batch(vec![b'n', b'o', b'p', b'e']);
    assert!(matches!(result, Err(CoreError::SerializationError { .. })));
}

#[test]
fn test_answers_round_trip_via_proto() {
    // Answers go over the wire as protobuf (not JSON): every field must survive the round trip.
    let answers = vec![
        WearosAnswer {
            id: "c1_0_1000".to_string(),
            note_id: 11,
            ord: 0,
            ease: 1,
            time_taken_ms: 4200,
            deck_id: 42,
        },
        WearosAnswer {
            id: "c2_1_2000".to_string(),
            note_id: 22,
            ord: 1,
            ease: 4,
            time_taken_ms: 900,
            deck_id: 99,
        },
    ];
    let restored = deserialize_answers(serialize_answers(answers).unwrap()).unwrap();
    assert_eq!(restored.len(), 2);
    assert_eq!(restored[0].id, "c1_0_1000");
    assert_eq!(restored[0].note_id, 11);
    assert_eq!(restored[0].ease, 1);
    assert_eq!(restored[0].time_taken_ms, 4200);
    assert_eq!(restored[0].deck_id, 42, "deck_id must survive the wire");
    assert_eq!(restored[1].ord, 1);
    assert_eq!(restored[1].ease, 4);
    assert_eq!(restored[1].deck_id, 99);
}

#[test]
fn test_empty_answers_round_trip() {
    let restored = deserialize_answers(serialize_answers(vec![]).unwrap()).unwrap();
    assert!(restored.is_empty());
}

#[test]
fn test_decks_round_trip_via_proto() {
    // Decks go over the wire as protobuf (not the old ad-hoc "id:name" text): a name containing
    // a colon or newline must survive, which is exactly what broke the text format.
    let decks = vec![
        DeckInfo { id: 1, full_name: "Japani::文法".to_string() },
        DeckInfo { id: 2, full_name: "Weird: Deck\nName".to_string() },
    ];
    let restored = deserialize_decks(serialize_decks(decks).unwrap()).unwrap();
    assert_eq!(restored.len(), 2);
    assert_eq!(restored[0].id, 1);
    assert_eq!(restored[0].full_name, "Japani::文法");
    assert_eq!(restored[1].id, 2);
    assert_eq!(restored[1].full_name, "Weird: Deck\nName");
}

#[test]
fn test_empty_decks_round_trip() {
    let restored = deserialize_decks(serialize_decks(vec![]).unwrap()).unwrap();
    assert!(restored.is_empty());
}

#[test]
fn test_deserialize_decks_rejects_garbage() {
    let result = deserialize_decks(vec![0xff, 0xff, 0xff, 0xff, 0xff]);
    assert!(matches!(result, Err(CoreError::SerializationError { .. })));
}

#[test]
fn test_deserialize_due_batch_rejects_decompression_bomb() {
    use std::io::Read;
    // A frame that inflates far past any real batch must be refused while decoding, not after
    // the allocation. 40MB of zeros compresses to a few KB but exceeds the 32MB cap.
    let bomb = zstd::encode_all(std::io::repeat(0u8).take(40 * 1024 * 1024), 3).unwrap();
    let Err(err) = deserialize_due_batch(bomb) else {
        panic!("a 40MB decompression bomb must be rejected");
    };
    assert!(err.to_string().contains("cap"), "expected the size-cap rejection, got: {err}");
}

#[test]
fn test_due_batch_round_trips_schedule_config_and_card_state() {
    let config = parse_deck_schedule_config(fsrs_deck_options()).unwrap();
    let card = WearosCard {
        id: 7,
        note_id: 70,
        ord: 0,
        front: "Q".to_string(),
        back: "A".to_string(),
        button_count: 4,
        next_review_times: vec!["1m".to_string()],
        due_day: None,
        due_at_secs: None,
        state: Some(review_state()),
        queued_day: None,
    };
    let batch = WearosDueBatch {
        deck_id: 1,
        deck_name: "D".to_string(),
        cards: vec![card],
        rollover_hour: 4,
        fetched_at_ms: 0,
        schedule_config: Some(config),
        queue_day: None,
    };

    let decoded = deserialize_due_batch(serialize_due_batch(batch).unwrap()).unwrap();
    let decoded_config = decoded.schedule_config.expect("config survives the wire");
    assert_secs(decoded_config.learn_steps[1], 600.0, "learning steps survive");
    assert_eq!(decoded_config.fsrs_weights.len(), 19, "weights survive");
    let decoded_state = decoded.cards[0].state.clone().expect("card state survives the wire");
    assert_eq!(decoded_state.queue, 2);
    assert_secs(decoded_state.stability, 10.0, "stability survives");
    assert_secs(decoded_state.elapsed_days, 10.0, "elapsed days survive");
}

#[test]
fn test_due_batch_without_scheduler_data_decodes_as_none() {
    // A phone build that predates the scheduler fields sends neither, and the watch must see
    // "absent" rather than an all-zero config it would compute wrong previews from.
    let batch = WearosDueBatch {
        deck_id: 1,
        deck_name: "D".to_string(),
        cards: vec![WearosCard {
            id: 7,
            note_id: 70,
            ord: 0,
            front: "Q".to_string(),
            back: "A".to_string(),
            button_count: 4,
            next_review_times: vec!["1m".to_string()],
            due_day: None,
            due_at_secs: None,
            state: None,
            queued_day: None,
        }],
        rollover_hour: 4,
        fetched_at_ms: 0,
        schedule_config: None,
        queue_day: None,
    };

    let decoded = deserialize_due_batch(serialize_due_batch(batch).unwrap()).unwrap();
    assert!(decoded.schedule_config.is_none());
    assert!(decoded.cards[0].state.is_none());
}

// ---- Multi-day pool wire fields (due dates, last review, rollover, daily limits) ----

#[test]
fn test_due_batch_round_trips_new_pool_fields() {
    let card = WearosCard {
        id: 7,
        note_id: 70,
        ord: 0,
        front: "Q".to_string(),
        back: "A".to_string(),
        button_count: 4,
        next_review_times: vec!["1m".to_string()],
        due_day: Some(20_733),
        due_at_secs: Some(1_791_331_200),
        state: Some(CardScheduleState { last_review_secs: 1_700_000_000, ..Default::default() }),
        queued_day: None,
    };
    let batch = WearosDueBatch {
        deck_id: 1,
        deck_name: "D".to_string(),
        cards: vec![card],
        schedule_config: Some(DeckScheduleConfig {
            new_per_day: 15,
            reviews_per_day: 150,
            ..Default::default()
        }),
        rollover_hour: 5,
        fetched_at_ms: 1_791_331_200_000,
        queue_day: None,
    };

    let decoded = deserialize_due_batch(serialize_due_batch(batch).unwrap()).unwrap();
    assert_eq!(decoded.rollover_hour, 5);
    assert_eq!(decoded.fetched_at_ms, 1_791_331_200_000);
    assert_eq!(decoded.cards[0].due_day, Some(20_733));
    assert_eq!(decoded.cards[0].due_at_secs, Some(1_791_331_200));
    assert_eq!(decoded.cards[0].state.as_ref().unwrap().last_review_secs, 1_700_000_000);
    let config = decoded.schedule_config.unwrap();
    assert_eq!(config.new_per_day, 15);
    assert_eq!(config.reviews_per_day, 150);
}

#[test]
fn test_zero_limits_and_midnight_rollover_survive_the_wire() {
    // A deck with new cards switched off and a midnight rollover are real settings, not "absent".
    let card = WearosCard {
        id: 1,
        note_id: 10,
        ord: 0,
        front: "Q".to_string(),
        back: "A".to_string(),
        button_count: 4,
        next_review_times: vec!["1m".to_string()],
        due_day: None,
        due_at_secs: None,
        state: Some(CardScheduleState { last_review_secs: 0, ..Default::default() }),
        queued_day: None,
    };
    let batch = WearosDueBatch {
        deck_id: 1,
        deck_name: "D".to_string(),
        cards: vec![card],
        schedule_config: Some(DeckScheduleConfig {
            new_per_day: 0,
            reviews_per_day: 0,
            ..Default::default()
        }),
        rollover_hour: 0,
        fetched_at_ms: 0,
        queue_day: None,
    };

    let decoded = deserialize_due_batch(serialize_due_batch(batch).unwrap()).unwrap();
    assert!(decoded.cards[0].due_day.is_none(), "absent due_day must stay None");
    assert!(decoded.cards[0].due_at_secs.is_none(), "absent due_at_secs must stay None");
    assert_eq!(decoded.cards[0].state.as_ref().unwrap().last_review_secs, 0);
    assert_eq!(decoded.fetched_at_ms, 0);
    assert_eq!(decoded.rollover_hour, 0, "a midnight rollover stays midnight");
    let config = decoded.schedule_config.unwrap();
    assert_eq!(config.new_per_day, 0, "a deck with new cards off stays off");
    assert_eq!(config.reviews_per_day, 0);
}
