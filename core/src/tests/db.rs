//! The local SQLite answer buffer: ordering, stats, pruning, migration, and connection pragmas.

use super::{cleanup_db, db_test_guard};
use crate::*;

#[test]
fn test_reviews_since() {
    let _guard = db_test_guard();
    let temp_dir = std::env::temp_dir();
    let db_path = temp_dir.join("test_wearos_reviewlog.db").to_string_lossy().to_string();
    let _ = std::fs::remove_file(&db_path);
    init_database(db_path.clone()).unwrap();

    assert_eq!(reviews_since(0).unwrap(), 0);
    for i in 0..3 {
        save_answer(WearosAnswer {
            id: format!("r{i}"),
            note_id: i,
            ord: 0,
            ease: 3,
            time_taken_ms: 1000,
            deck_id: 0,
        })
        .unwrap();
    }
    // All three logged "now", so counted from epoch 0; none from far in the future.
    assert_eq!(reviews_since(0).unwrap(), 3);
    assert_eq!(reviews_since(i64::MAX).unwrap(), 0);

    let _ = std::fs::remove_file(&db_path);
}

#[test]
fn test_average_answer_time_ms() {
    let _guard = db_test_guard();
    let temp_dir = std::env::temp_dir();
    let db_path = temp_dir.join("test_wearos_avg.db").to_string_lossy().to_string();
    let _ = std::fs::remove_file(&db_path);
    init_database(db_path.clone()).unwrap();

    // Empty buffer averages to None (queue-less days shouldn't skew the estimate).
    assert_eq!(average_answer_time_ms().unwrap(), None);

    for (i, ms) in [4000_i64, 6000, 8000].iter().enumerate() {
        save_answer(WearosAnswer {
            id: format!("avg_{i}"),
            note_id: 100 + i as i64,
            ord: 0,
            ease: 3,
            time_taken_ms: *ms,
            deck_id: 0,
        })
        .unwrap();
    }
    assert_eq!(average_answer_time_ms().unwrap(), Some(6000));

    let _ = std::fs::remove_file(&db_path);
}

#[test]
fn test_buffered_answer_stats() {
    let _guard = db_test_guard();
    let temp_dir = std::env::temp_dir();
    let db_path = temp_dir.join("test_wearos_stats.db").to_string_lossy().to_string();
    let _ = std::fs::remove_file(&db_path);
    init_database(db_path.clone()).unwrap();

    // Empty buffer: zero count, zero age.
    let empty = buffered_answer_stats().unwrap();
    assert_eq!(empty.count, 0);
    assert_eq!(empty.oldest_age_secs, 0);

    save_answer(WearosAnswer {
        id: "s1".to_string(),
        note_id: 1,
        ord: 0,
        ease: 3,
        time_taken_ms: 1000,
        deck_id: 0,
    })
    .unwrap();
    save_answer(WearosAnswer {
        id: "s2".to_string(),
        note_id: 2,
        ord: 0,
        ease: 2,
        time_taken_ms: 2000,
        deck_id: 0,
    })
    .unwrap();
    let stats = buffered_answer_stats().unwrap();
    assert_eq!(stats.count, 2);
    assert!(stats.oldest_age_secs >= 0, "age should be non-negative");

    let _ = std::fs::remove_file(&db_path);
}

#[test]
fn test_buffer_error_paths_without_init() {
    let _guard = db_test_guard();
    // Point the shared connection at nothing, mimicking a process that never called
    // init_database. Every buffer op must surface a DatabaseError rather than panic.
    *crate::db::DB_CONN.lock().unwrap() = None;
    assert!(matches!(save_answer(sample_answer()), Err(CoreError::DatabaseError { .. })));
    assert!(matches!(get_buffered_answers(), Err(CoreError::DatabaseError { .. })));
    assert!(matches!(average_answer_time_ms(), Err(CoreError::DatabaseError { .. })));
    assert!(matches!(buffered_answer_stats(), Err(CoreError::DatabaseError { .. })));
    assert!(matches!(
        remove_buffered_answers(vec!["x".to_string()]),
        Err(CoreError::DatabaseError { .. })
    ));
}

fn sample_answer() -> WearosAnswer {
    WearosAnswer {
        id: "s".to_string(),
        note_id: 1,
        ord: 0,
        ease: 3,
        time_taken_ms: 1000,
        deck_id: 0,
    }
}

#[test]
fn test_local_sqlite_buffering() {
    let _guard = db_test_guard();
    let temp_dir = std::env::temp_dir();
    let db_path = temp_dir.join("test_wearos_buffer.db").to_string_lossy().to_string();

    // Clean start
    let _ = std::fs::remove_file(&db_path);

    init_database(db_path.clone()).unwrap();

    let ans1 = WearosAnswer {
        id: "id_1".to_string(),
        note_id: 101,
        ord: 0,
        ease: 3,
        time_taken_ms: 4500,
        deck_id: 555,
    };
    let ans2 = WearosAnswer {
        id: "id_2".to_string(),
        note_id: 102,
        ord: 1,
        ease: 1,
        time_taken_ms: 12000,
        deck_id: 666,
    };

    save_answer(ans1).unwrap();
    save_answer(ans2).unwrap();

    let buffered = get_buffered_answers().unwrap();
    assert_eq!(buffered.len(), 2);
    assert_eq!(buffered[0].id, "id_1");
    assert_eq!(buffered[1].id, "id_2");
    assert_eq!(buffered[0].deck_id, 555, "deck_id must persist through the buffer");
    assert_eq!(buffered[1].deck_id, 666);

    remove_buffered_answers(vec!["id_1".to_string()]).unwrap();
    let buffered_after = get_buffered_answers().unwrap();
    assert_eq!(buffered_after.len(), 1);
    assert_eq!(buffered_after[0].id, "id_2");

    // Clean up
    let _ = std::fs::remove_file(&db_path);
}

#[test]
fn test_same_second_answers_keep_insertion_order() {
    // created_at has 1-second resolution, so grades answered back to back in a learning chain can
    // share a timestamp; rowid breaks the tie. Swapping them would hand write-back a chain out of
    // order, which it must never apply.
    let _guard = db_test_guard();
    let db_path =
        std::env::temp_dir().join("test_wearos_same_second.db").to_string_lossy().to_string();
    cleanup_db(&db_path);
    init_database(db_path.clone()).unwrap();

    let ids = ["first", "second", "third", "fourth"];
    for (i, id) in ids.iter().enumerate() {
        save_answer(WearosAnswer {
            id: id.to_string(),
            note_id: 7,
            ord: 0,
            ease: 3,
            time_taken_ms: 100 + i as i64,
            deck_id: 1,
        })
        .unwrap();
    }

    let buffered = get_buffered_answers().unwrap();
    let got: Vec<&str> = buffered.iter().map(|a| a.id.as_str()).collect();
    assert_eq!(got, ids, "same-second answers must come back in insertion order");

    cleanup_db(&db_path);
}

#[test]
fn test_record_unplaced_only_touches_reported_ids() {
    let _guard = db_test_guard();
    let db_path =
        std::env::temp_dir().join("test_wearos_unplaced_ids.db").to_string_lossy().to_string();
    cleanup_db(&db_path);
    init_database(db_path.clone()).unwrap();

    let mk = |id: &str| WearosAnswer {
        id: id.to_string(),
        note_id: 1,
        ord: 0,
        ease: 3,
        time_taken_ms: 100,
        deck_id: 1,
    };
    save_answer(mk("reported")).unwrap();
    save_answer(mk("untouched")).unwrap();

    // Even well past the cap, only the reported id is touched: the other buffered answer keeps
    // zero attempts and is never pruned.
    for _ in 0..(crate::db::MAX_SEND_ATTEMPTS + 2) {
        assert_eq!(record_unplaced_answers(vec!["reported".to_string()]).unwrap(), 0);
    }

    let conn = crate::db::get_db_connection().unwrap();
    let reported: i64 = conn
        .query_row("SELECT send_attempts FROM buffered_answers WHERE id = 'reported'", [], |r| {
            r.get(0)
        })
        .unwrap();
    let untouched: i64 = conn
        .query_row("SELECT send_attempts FROM buffered_answers WHERE id = 'untouched'", [], |r| {
            r.get(0)
        })
        .unwrap();
    drop(conn);
    assert_eq!(reported, crate::db::MAX_SEND_ATTEMPTS + 2);
    assert_eq!(untouched, 0, "an unreported answer must never accrue attempts");
    assert_eq!(get_buffered_answers().unwrap().len(), 2, "both stay buffered while young");

    cleanup_db(&db_path);
}

#[test]
fn test_recent_answer_is_not_pruned_even_past_the_attempt_cap() {
    let _guard = db_test_guard();
    let db_path =
        std::env::temp_dir().join("test_wearos_recent_unplaced.db").to_string_lossy().to_string();
    cleanup_db(&db_path);
    init_database(db_path.clone()).unwrap();

    save_answer(WearosAnswer {
        id: "young".to_string(),
        note_id: 1,
        ord: 0,
        ease: 3,
        time_taken_ms: 100,
        deck_id: 1,
    })
    .unwrap();

    // A learning step can leave an answer unplaceable for a while, so repeated syncs past the cap
    // must not prune it while it is younger than the minimum orphan age.
    for _ in 0..(crate::db::MAX_SEND_ATTEMPTS + 5) {
        assert_eq!(record_unplaced_answers(vec!["young".to_string()]).unwrap(), 0);
    }
    assert_eq!(get_buffered_answers().unwrap().len(), 1);

    cleanup_db(&db_path);
}

#[test]
fn test_old_answer_past_the_cap_is_pruned_and_counted() {
    let _guard = db_test_guard();
    let db_path =
        std::env::temp_dir().join("test_wearos_old_unplaced.db").to_string_lossy().to_string();
    cleanup_db(&db_path);
    init_database(db_path.clone()).unwrap();

    // Age the row directly so it is past the minimum orphan age; it starts one below the cap.
    let conn = crate::db::get_db_connection().unwrap();
    conn.execute(
        "INSERT INTO buffered_answers
             (id, note_id, card_ord, ease, time_taken_ms, created_at, deck_id, send_attempts)
         VALUES ('orphan', 1, 0, 3, 100, ?1, 1, ?2)",
        rusqlite::params![
            crate::util::unix_secs() - crate::db::MIN_ORPHAN_AGE_SECS - 60,
            crate::db::MAX_SEND_ATTEMPTS
        ],
    )
    .unwrap();
    drop(conn);

    // One more failed placement takes it over the cap; old enough now, so it is pruned and counted.
    assert_eq!(record_unplaced_answers(vec!["orphan".to_string()]).unwrap(), 1);
    assert_eq!(get_buffered_answers().unwrap().len(), 0);

    cleanup_db(&db_path);
}

#[test]
fn test_db_connections_get_busy_timeout_and_wal() {
    // Three components share this DB file via short-lived connections (Activity grading,
    // WearSyncService clearing, Tile stats). SQLite's default 0ms busy timeout fails a write
    // instantly on collision; every connection must come configured to wait instead.
    let _guard = db_test_guard();
    let db_path = std::env::temp_dir().join("test_wearos_busy.db").to_string_lossy().to_string();
    cleanup_db(&db_path);

    init_database(db_path.clone()).unwrap();
    let conn = crate::db::get_db_connection().unwrap();
    let timeout_ms: i64 = conn.query_row("PRAGMA busy_timeout", [], |r| r.get(0)).unwrap();
    assert_eq!(timeout_ms, 5000, "busy_timeout must be applied to every connection");
    let mode: String = conn.query_row("PRAGMA journal_mode", [], |r| r.get(0)).unwrap();
    assert_eq!(mode, "wal", "init must switch the DB to WAL journaling");

    drop(conn);
    cleanup_db(&db_path);
}

#[test]
fn test_buffer_migration_adds_deck_id_to_legacy_db() {
    let _guard = db_test_guard();
    let db_path = std::env::temp_dir().join("test_wearos_legacy.db").to_string_lossy().to_string();
    let _ = std::fs::remove_file(&db_path);

    // Simulate a buffer created before deck_id existed: the old schema + a queued row.
    {
        let conn = rusqlite::Connection::open(&db_path).unwrap();
        conn.execute(
            "CREATE TABLE buffered_answers (id TEXT PRIMARY KEY, note_id INTEGER NOT NULL, \
             card_ord INTEGER NOT NULL, ease INTEGER NOT NULL, time_taken_ms INTEGER NOT NULL, \
             created_at INTEGER NOT NULL)",
            [],
        )
        .unwrap();
        conn.execute(
            "INSERT INTO buffered_answers (id, note_id, card_ord, ease, time_taken_ms, created_at) \
             VALUES ('legacy', 7, 0, 3, 1000, 1)",
            [],
        )
        .unwrap();
    }

    // init_database must ADD COLUMN deck_id without dropping the pre-existing row.
    init_database(db_path.clone()).unwrap();
    let buffered = get_buffered_answers().unwrap();
    assert_eq!(buffered.len(), 1, "legacy row must survive the migration");
    assert_eq!(buffered[0].id, "legacy");
    assert_eq!(buffered[0].deck_id, 0, "legacy rows default to unknown deck (0)");

    let _ = std::fs::remove_file(&db_path);
}
