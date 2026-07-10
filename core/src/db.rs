//! SQLite-backed local buffer for card answers, plus the connection bootstrap.

use crate::models::{CoreError, WearosAnswer};
use crate::util::unix_secs;
use rusqlite::{Connection, params};
use std::sync::Mutex;

// `pub` within this private, un-re-exported module = crate-internal. Reached as `crate::db::DB_CONN`
// (e.g. the tests reset it to simulate an uninitialized process); never part of the FFI surface.
pub static DB_CONN: Mutex<Option<String>> = Mutex::new(None);

#[uniffi::export]
pub fn init_database(db_path: String) -> Result<(), CoreError> {
    let conn = Connection::open(&db_path)?;

    conn.execute(
        "CREATE TABLE IF NOT EXISTS buffered_answers (
            id TEXT PRIMARY KEY,
            note_id INTEGER NOT NULL,
            card_ord INTEGER NOT NULL,
            ease INTEGER NOT NULL,
            time_taken_ms INTEGER NOT NULL,
            created_at INTEGER NOT NULL
        )",
        [],
    )?;

    // Append-only activity log for the "N reviewed today" counter. Independent of buffered_answers
    // so it survives sync-back clearing (buffered_answers is drained once the phone applies them).
    conn.execute("CREATE TABLE IF NOT EXISTS review_log (answered_at INTEGER NOT NULL)", [])?;

    {
        let mut db = DB_CONN.lock().unwrap();
        *db = Some(db_path);
    }

    Ok(())
}

// Crate-internal helper the session module shares (via `crate::db::get_db_connection`); `pub` in
// this private, un-re-exported module keeps it off the FFI surface without a lint suppression.
pub fn get_db_connection() -> Result<Connection, CoreError> {
    // Clone the path out of the guard in one statement so the lock releases before the blocking open.
    let Some(path) = DB_CONN.lock().unwrap().clone() else {
        return Err(CoreError::DatabaseError {
            error_message: "Database has not been initialized. Call init_database first."
                .to_string(),
        });
    };
    Ok(Connection::open(&path)?)
}

#[uniffi::export]
pub fn save_answer(answer: WearosAnswer) -> Result<(), CoreError> {
    let conn = get_db_connection()?;
    let now = unix_secs();

    conn.execute(
        "INSERT OR REPLACE INTO buffered_answers (id, note_id, card_ord, ease, time_taken_ms, created_at)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
        params![
            answer.id,
            answer.note_id,
            answer.ord,
            answer.ease,
            answer.time_taken_ms,
            now
        ],
    )?;

    // Log the activity for the daily counter (append-only; not undone/cleared).
    conn.execute("INSERT INTO review_log (answered_at) VALUES (?1)", params![now])?;

    Ok(())
}

/// Number of reviews logged at or after `since_secs` (epoch seconds). The caller passes local
/// midnight to get "reviewed today" — keeping timezone handling on the Kotlin side where it's known.
#[uniffi::export]
pub fn reviews_since(since_secs: i64) -> Result<i32, CoreError> {
    let conn = get_db_connection()?;
    let count: i64 = conn.query_row(
        "SELECT COUNT(*) FROM review_log WHERE answered_at >= ?1",
        params![since_secs],
        |r| r.get(0),
    )?;
    Ok(count as i32)
}

#[uniffi::export]
pub fn get_buffered_answers() -> Result<Vec<WearosAnswer>, CoreError> {
    let conn = get_db_connection()?;
    let mut stmt = conn.prepare(
        "SELECT id, note_id, card_ord, ease, time_taken_ms FROM buffered_answers ORDER BY created_at ASC",
    )?;

    let rows = stmt.query_map([], |row| {
        Ok(WearosAnswer {
            id: row.get(0)?,
            note_id: row.get(1)?,
            ord: row.get(2)?,
            ease: row.get(3)?,
            time_taken_ms: row.get(4)?,
        })
    })?;

    let mut answers = Vec::new();
    for row in rows {
        answers.push(row?);
    }

    Ok(answers)
}

/// Snapshot of the local answer buffer for status UI: how many reviews are waiting to reach the
/// phone, and how old the oldest one is (seconds; 0 when the buffer is empty).
#[derive(uniffi::Record)]
pub struct BufferStats {
    pub count: i32,
    pub oldest_age_secs: i64,
}

/// Count + oldest age of the buffered (un-synced) answers, so the watch can nudge the user when
/// reviews have been sitting un-synced for a while.
#[uniffi::export]
pub fn buffered_answer_stats() -> Result<BufferStats, CoreError> {
    let conn = get_db_connection()?;
    let (count, oldest): (i64, Option<i64>) =
        conn.query_row("SELECT COUNT(*), MIN(created_at) FROM buffered_answers", [], |row| {
            Ok((row.get(0)?, row.get(1)?))
        })?;
    let oldest_age_secs = oldest.map_or(0, |t| (unix_secs() - t).max(0));
    Ok(BufferStats { count: count as i32, oldest_age_secs })
}

/// Mean answer time (ms) over the currently-buffered answers, or `None` when the buffer is empty.
/// Lets the Tile replace its flat per-card estimate with the user's real observed pace.
#[uniffi::export]
pub fn average_answer_time_ms() -> Result<Option<i64>, CoreError> {
    let conn = get_db_connection()?;
    // AVG over no rows is SQL NULL, which maps cleanly to None via Option<f64>.
    let avg: Option<f64> =
        conn.query_row("SELECT AVG(time_taken_ms) FROM buffered_answers", [], |row| row.get(0))?;
    Ok(avg.map(|a| a.round() as i64))
}

#[uniffi::export]
pub fn remove_buffered_answers(ids: Vec<String>) -> Result<(), CoreError> {
    let mut conn = get_db_connection()?;
    let tx = conn.transaction()?;

    for id in ids {
        tx.execute("DELETE FROM buffered_answers WHERE id = ?1", params![id])?;
    }

    tx.commit()?;
    Ok(())
}
