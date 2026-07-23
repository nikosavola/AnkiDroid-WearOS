//! SQLite-backed local buffer for card answers, plus the connection bootstrap.

use crate::models::{CoreError, WearosAnswer};
use crate::util::unix_secs;
use rusqlite::{Connection, params};
use std::sync::Mutex;
use std::time::Duration;

// `pub` within this private, un-re-exported module = crate-internal. Reached as `crate::db::DB_CONN`
// (e.g. the tests reset it to simulate an uninitialized process); never part of the FFI surface.
pub static DB_CONN: Mutex<Option<String>> = Mutex::new(None);

/// How long a connection waits on a locked database before giving up. Every caller opens its own
/// short-lived [`Connection`], and three components share the file (the Activity buffering grades,
/// WearSyncService clearing them on sync, the Tile reading stats); SQLite's default is 0ms, so a
/// collision would fail a write instantly with SQLITE_BUSY instead of just waiting its turn.
const BUSY_TIMEOUT: Duration = Duration::from_secs(5);

/// How far back [`init_database`] keeps review_log rows (30 days). The counter only ever reports
/// "since local midnight", so anything older is dead weight; a wide window is kept in case a
/// weekly/monthly stat wants it later.
const REVIEW_LOG_RETENTION_SECS: i64 = 30 * 24 * 60 * 60;

/// How many times a buffered answer may be sent to the phone without settling before it is
/// discarded. A genuinely applicable answer clears within a sync or two; one that keeps coming back
/// (card deleted / suspended / never reaching the deck head) would otherwise be re-sent forever.
const MAX_SEND_ATTEMPTS: i64 = 8;

/// Open a connection with the crate's shared settings applied (see [`BUSY_TIMEOUT`]).
fn open_configured(path: &str) -> Result<Connection, CoreError> {
    let conn = Connection::open(path)?;
    conn.busy_timeout(BUSY_TIMEOUT)?;
    Ok(conn)
}

/// Whether `table` already has `column`. Used to make `ADD COLUMN` migrations idempotent (a bare
/// ALTER errors if the column exists, and fresh DBs get every column from CREATE TABLE).
fn column_exists(conn: &Connection, table: &str, column: &str) -> Result<bool, CoreError> {
    Ok(conn
        .prepare(&format!("PRAGMA table_info({table})"))?
        .query_map([], |r| r.get::<_, String>(1))?
        .filter_map(Result::ok)
        .any(|name| name == column))
}

#[uniffi::export]
pub fn init_database(db_path: String) -> Result<(), CoreError> {
    let conn = open_configured(&db_path)?;

    // WAL journaling (persisted in the DB file, so setting it once here covers every later
    // connection): readers don't block the writer, which is the actual contention pattern here
    // (Tile/UI stats reads vs. grade writes vs. the service's clear transaction).
    let _mode: String = conn.query_row("PRAGMA journal_mode=WAL", [], |r| r.get(0))?;

    conn.execute(
        "CREATE TABLE IF NOT EXISTS buffered_answers (
            id TEXT PRIMARY KEY,
            note_id INTEGER NOT NULL,
            card_ord INTEGER NOT NULL,
            ease INTEGER NOT NULL,
            time_taken_ms INTEGER NOT NULL,
            created_at INTEGER NOT NULL,
            deck_id INTEGER NOT NULL DEFAULT 0,
            send_attempts INTEGER NOT NULL DEFAULT 0
        )",
        [],
    )?;

    // Migrate older buffers by adding any missing columns (defaults keep already-queued rows valid).
    // deck_id: 0 = unknown deck. send_attempts: the H4 orphan cap (see bump_send_attempts_and_prune).
    if !column_exists(&conn, "buffered_answers", "deck_id")? {
        conn.execute(
            "ALTER TABLE buffered_answers ADD COLUMN deck_id INTEGER NOT NULL DEFAULT 0",
            [],
        )?;
    }
    if !column_exists(&conn, "buffered_answers", "send_attempts")? {
        conn.execute(
            "ALTER TABLE buffered_answers ADD COLUMN send_attempts INTEGER NOT NULL DEFAULT 0",
            [],
        )?;
    }

    // Append-only activity log for the "N reviewed today" counter. Independent of buffered_answers
    // so it survives sync-back clearing (buffered_answers is drained once the phone applies them).
    conn.execute("CREATE TABLE IF NOT EXISTS review_log (answered_at INTEGER NOT NULL)", [])?;

    // The log only ever answers "how many since local midnight", so prune entries beyond a
    // generous window on each init - otherwise it grows one row per review forever on a
    // storage-constrained watch (years of reviews scanned by every COUNT).
    conn.execute(
        "DELETE FROM review_log WHERE answered_at < ?1",
        params![unix_secs() - REVIEW_LOG_RETENTION_SECS],
    )?;

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
    open_configured(&path)
}

#[uniffi::export]
pub fn save_answer(answer: WearosAnswer) -> Result<(), CoreError> {
    let mut conn = get_db_connection()?;
    let now = unix_secs();

    // One transaction so the buffered answer and its daily-counter row land (or fail) together,
    // instead of the counter drifting when the second insert hits an error.
    let tx = conn.transaction()?;
    tx.execute(
        "INSERT OR REPLACE INTO buffered_answers
             (id, note_id, card_ord, ease, time_taken_ms, created_at, deck_id)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)",
        params![
            answer.id,
            answer.note_id,
            answer.ord,
            answer.ease,
            answer.time_taken_ms,
            now,
            answer.deck_id
        ],
    )?;
    // Log the activity for the daily counter (append-only; not undone/cleared).
    tx.execute("INSERT INTO review_log (answered_at) VALUES (?1)", params![now])?;
    tx.commit()?;

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
        "SELECT id, note_id, card_ord, ease, time_taken_ms, deck_id
         FROM buffered_answers ORDER BY created_at ASC",
    )?;

    let rows = stmt.query_map([], |row| {
        Ok(WearosAnswer {
            id: row.get(0)?,
            note_id: row.get(1)?,
            ord: row.get(2)?,
            ease: row.get(3)?,
            time_taken_ms: row.get(4)?,
            deck_id: row.get(5)?,
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

/// Count one send attempt against every buffered answer and drop those sent too many times.
///
/// Call once per sync, right before sending: an answer that reaches the phone and applies is cleared
/// via `/clear_answers` before the next sync, so only genuinely unplaceable answers (orphans) ever
/// accumulate [`MAX_SEND_ATTEMPTS`] attempts and get pruned. Returns how many were discarded so the
/// caller can surface it.
#[uniffi::export]
pub fn bump_send_attempts_and_prune() -> Result<u32, CoreError> {
    let mut conn = get_db_connection()?;
    let tx = conn.transaction()?;
    tx.execute("UPDATE buffered_answers SET send_attempts = send_attempts + 1", [])?;
    let discarded = tx.execute(
        "DELETE FROM buffered_answers WHERE send_attempts > ?1",
        params![MAX_SEND_ATTEMPTS],
    )?;
    tx.commit()?;
    Ok(discarded as u32)
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
