//! On-watch multi-day card pool: the phone ships a deck's whole due pool (today's scheduler batch,
//! intraday learning cards, upcoming days, and new cards) and the watch stores it in SQLite so it
//! can build each day's queue offline, the way Anki would, for the days the phone is not reachable.
//!
//! AnkiDroid still owns the real schedule: a queue built here is advisory and every grade is
//! reconciled by AnkiDroid on write-back. This module only decides *what is due today* from the
//! stored pool and refreshes the interval previews the watch UI shows.

use crate::day::{day_index, elapsed_days_between};
use crate::db::get_db_connection;
use crate::models::{CoreError, WearosCard, WearosDueBatch};
use crate::proto::{decode_card, decode_deck_config, encode_card, encode_deck_config};
use crate::sched::{DeckScheduleConfig, preview_labels_for_buttons};
use rusqlite::{Connection, OptionalExtension, params};
use std::cmp::Ordering;

/// Anki's learn-ahead window: a learning card due within this many seconds of `now` joins today's
/// queue so the user can start it early rather than waiting for the exact step time.
pub const LEARN_AHEAD_SECS: i64 = 1_200;

/// `grade_log.kind` values: the kind of card *before* the grade. 1 (learning/relearning) is written
/// by the grading task; the queue only counts new (0) and review (2) against the daily limits.
pub const GRADE_KIND_NEW: i64 = 0;
pub const GRADE_KIND_LEARNING: i64 = 1;
pub const GRADE_KIND_REVIEW: i64 = 2;

/// AnkiDroid's `queue` value for intraday (sub-day) learning cards.
const QUEUE_INTRADAY_LEARN: i32 = 1;

/// A deck's stored pool summary for the watch's deck list.
#[derive(uniffi::Record)]
pub struct PoolDeckSummary {
    pub deck_id: i64,
    pub name: String,
    /// Cards [`pool_today_batch`] would return right now.
    pub due_now: i32,
    /// Learning cards due later today, beyond the learn-ahead window.
    pub learning_later: i32,
    /// When the next of those is due (epoch seconds), if any.
    pub next_learning_due_secs: Option<i64>,
    pub total_cards: i32,
}

/// Store or refresh a deck's whole pool.
///
/// Replaces the deck's stored cards with the batch's, except that any card still carrying a
/// buffered answer keeps its existing local row: the watch's own state is newer than the phone's
/// until that answer is applied.
#[uniffi::export]
pub fn pool_store_batch(batch: WearosDueBatch) -> Result<(), CoreError> {
    let mut conn = get_db_connection()?;
    let tx = conn.transaction()?;
    let deck_id = batch.deck_id;

    tx.execute(
        "INSERT OR REPLACE INTO pool_decks (deck_id, name, config, rollover_hour, fetched_at_ms)
         VALUES (?1, ?2, ?3, ?4, ?5)",
        params![
            deck_id,
            batch.deck_name,
            batch.schedule_config.map(encode_deck_config),
            batch.rollover_hour,
            batch.fetched_at_ms
        ],
    )?;

    // Drop the deck's stored rows, keeping any whose card has a buffered answer (matched on note +
    // template ordinal, which is unique per card in Anki).
    tx.execute(
        "DELETE FROM pool_cards
          WHERE deck_id = ?1
            AND NOT EXISTS (
                SELECT 1 FROM buffered_answers b
                WHERE b.note_id = pool_cards.note_id AND b.card_ord = pool_cards.ord
            )",
        params![deck_id],
    )?;

    for (position, card) in batch.cards.into_iter().enumerate() {
        let card_id = card.id;
        let note_id = card.note_id;
        let ord = card.ord;
        let due_day = card.due_day;
        let due_at_secs = card.due_at_secs;
        let is_new = card.state.as_ref().is_some_and(|s| s.queue == 0);
        let blob = encode_card(card);
        // OR IGNORE keeps the kept local row on the card_id conflict; the rest were just deleted.
        tx.execute(
            "INSERT OR IGNORE INTO pool_cards
                 (card_id, deck_id, note_id, ord, position, card, due_day, due_at_secs, is_new, graded)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, 0)",
            params![
                card_id,
                deck_id,
                note_id,
                ord,
                position as i64,
                blob,
                due_day,
                due_at_secs,
                is_new
            ],
        )?;
    }

    tx.commit()?;
    Ok(())
}

/// Forget every pooled deck (and its cards) not in `keep_deck_ids`, so unselected decks don't
/// linger on the watch after the phone's filtering changes.
#[uniffi::export]
pub fn pool_forget_decks(keep_deck_ids: Vec<i64>) -> Result<(), CoreError> {
    let mut conn = get_db_connection()?;
    let tx = conn.transaction()?;

    if keep_deck_ids.is_empty() {
        tx.execute("DELETE FROM pool_cards", [])?;
        tx.execute("DELETE FROM pool_decks", [])?;
    } else {
        let placeholders = vec!["?"; keep_deck_ids.len()].join(", ");
        tx.execute(
            &format!("DELETE FROM pool_cards WHERE deck_id NOT IN ({placeholders})"),
            rusqlite::params_from_iter(keep_deck_ids.iter()),
        )?;
        tx.execute(
            &format!("DELETE FROM pool_decks WHERE deck_id NOT IN ({placeholders})"),
            rusqlite::params_from_iter(keep_deck_ids.iter()),
        )?;
    }

    tx.commit()?;
    Ok(())
}

/// Whether a deck is currently pooled. Lets the caller skip a queue fetch for a deck the watch has
/// never been sent (or has since forgotten).
#[uniffi::export]
pub fn pool_has_deck(deck_id: i64) -> Result<bool, CoreError> {
    let conn = get_db_connection()?;
    let count: i64 = conn.query_row(
        "SELECT COUNT(*) FROM pool_decks WHERE deck_id = ?1",
        params![deck_id],
        |r| r.get(0),
    )?;
    Ok(count > 0)
}

/// Every card id stored in the pool, so the watch can prune its cached card images against it.
#[uniffi::export]
pub fn pool_card_ids() -> Result<Vec<i64>, CoreError> {
    let conn = get_db_connection()?;
    let mut stmt = conn.prepare("SELECT card_id FROM pool_cards")?;
    let ids = stmt.query_map([], |r| r.get::<_, i64>(0))?.collect::<Result<Vec<_>, _>>()?;
    Ok(ids)
}

/// Summaries for the watch's deck list: how much is due now, how much learning arrives later today,
/// and how big the deck is. Sorted by name, case-insensitive.
#[uniffi::export]
pub fn pool_deck_summaries(
    now_ms: i64,
    utc_offset_secs: i32,
) -> Result<Vec<PoolDeckSummary>, CoreError> {
    let conn = get_db_connection()?;
    let mut stmt = conn.prepare("SELECT deck_id, name, rollover_hour FROM pool_decks")?;
    let decks: Vec<(i64, String, i32)> = stmt
        .query_map([], |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)))?
        .collect::<Result<Vec<_>, _>>()?;
    drop(stmt);

    let mut summaries = Vec::with_capacity(decks.len());
    for (deck_id, name, rollover_hour) in decks {
        let due_now = today_batch_for(&conn, deck_id, now_ms, utc_offset_secs)?.cards.len();
        let total_cards: i64 = conn.query_row(
            "SELECT COUNT(*) FROM pool_cards WHERE deck_id = ?1",
            params![deck_id],
            |r| r.get(0),
        )?;
        let (learning_later, next_learning_due_secs) =
            learning_later_stats(&conn, deck_id, now_ms, utc_offset_secs, rollover_hour)?;
        summaries.push(PoolDeckSummary {
            deck_id,
            name,
            due_now: due_now as i32,
            learning_later,
            next_learning_due_secs,
            total_cards: total_cards as i32,
        });
    }

    summaries.sort_by_cached_key(|s| s.name.to_lowercase());
    Ok(summaries)
}

/// Build today's queue for one deck.
///
/// Learning cards come when due (within the learn-ahead window), then review cards due today or
/// overdue up to the daily review limit, then new cards up to the daily new limit. See
/// [`crate::pool`] for how the pool is stored.
#[uniffi::export]
pub fn pool_today_batch(
    deck_id: i64,
    now_ms: i64,
    utc_offset_secs: i32,
) -> Result<WearosDueBatch, CoreError> {
    let conn = get_db_connection()?;
    today_batch_for(&conn, deck_id, now_ms, utc_offset_secs)
}

/// A card as stored in the pool, plus the columns that decide which bucket it falls into.
struct PoolRow {
    card: WearosCard,
    due_day: Option<i64>,
    due_at_secs: Option<i64>,
    is_new: bool,
    /// Set once the card has been graded locally since it was stored, which invalidates the phone's
    /// shipped interval labels.
    graded: bool,
    position: i64,
}

fn today_batch_for(
    conn: &Connection,
    deck_id: i64,
    now_ms: i64,
    utc_offset_secs: i32,
) -> Result<WearosDueBatch, CoreError> {
    let stored = load_deck(conn, deck_id)?;
    let config = stored.config.map(|b| decode_deck_config(&b)).transpose()?;
    let today = day_index(now_ms, utc_offset_secs, stored.rollover_hour);
    let fetched_day = day_index(stored.fetched_at_ms, utc_offset_secs, stored.rollover_hour);
    let learn_cutoff = now_ms.div_euclid(1_000) + LEARN_AHEAD_SECS;

    let queue = build_today_queue(
        load_pool_rows(conn, deck_id)?,
        config.as_ref(),
        today,
        learn_cutoff,
        graded_today(conn, deck_id, today, GRADE_KIND_REVIEW)?,
        graded_today(conn, deck_id, today, GRADE_KIND_NEW)?,
    );
    let cards = queue
        .into_iter()
        .map(|row| {
            finalize_card(
                row,
                config.as_ref(),
                now_ms,
                utc_offset_secs,
                stored.rollover_hour,
                today,
                fetched_day,
            )
        })
        .collect();

    Ok(WearosDueBatch {
        deck_id,
        deck_name: stored.name,
        cards,
        schedule_config: config,
        rollover_hour: stored.rollover_hour,
        fetched_at_ms: stored.fetched_at_ms,
    })
}

/// The deck's stored row: name, config bytes, rollover hour, and fetch time.
struct StoredDeck {
    name: String,
    config: Option<Vec<u8>>,
    rollover_hour: i32,
    fetched_at_ms: i64,
}

fn load_deck(conn: &Connection, deck_id: i64) -> Result<StoredDeck, CoreError> {
    Ok(conn.query_row(
        "SELECT name, config, rollover_hour, fetched_at_ms FROM pool_decks WHERE deck_id = ?1",
        params![deck_id],
        |r| {
            Ok(StoredDeck {
                name: r.get(0)?,
                config: r.get(1)?,
                rollover_hour: r.get(2)?,
                fetched_at_ms: r.get(3)?,
            })
        },
    )?)
}

fn load_pool_rows(conn: &Connection, deck_id: i64) -> Result<Vec<PoolRow>, CoreError> {
    let mut stmt = conn.prepare(
        "SELECT card, due_day, due_at_secs, is_new, graded, position
         FROM pool_cards WHERE deck_id = ?1",
    )?;
    let mapped = stmt.query_map(params![deck_id], |r| {
        Ok((
            r.get::<_, Vec<u8>>(0)?,
            r.get::<_, Option<i64>>(1)?,
            r.get::<_, Option<i64>>(2)?,
            r.get::<_, i64>(3)? != 0,
            r.get::<_, i64>(4)? != 0,
            r.get::<_, i64>(5)?,
        ))
    })?;

    let mut rows = Vec::new();
    for row in mapped {
        let (blob, due_day, due_at_secs, is_new, graded, position) = row?;
        rows.push(PoolRow {
            card: decode_card(&blob)?,
            due_day,
            due_at_secs,
            is_new,
            graded,
            position,
        });
    }
    Ok(rows)
}

/// Split the stored rows into today's learning, review, and new buckets, order each the way Anki
/// would, apply the daily limits, and concatenate them into the queue.
fn build_today_queue(
    rows: Vec<PoolRow>,
    config: Option<&DeckScheduleConfig>,
    today: i64,
    learn_cutoff: i64,
    reviews_graded: i64,
    new_graded: i64,
) -> Vec<PoolRow> {
    let mut learning = Vec::new();
    let mut reviews = Vec::new();
    let mut new_cards = Vec::new();
    for row in rows {
        if let Some(at) = row.due_at_secs {
            // A learning card past the window waits for a later queue; one beyond today is dropped.
            if at <= learn_cutoff {
                learning.push(row);
            }
        } else if is_intraday_learning(&row) {
            // Today's batch ships intraday learning cards without a due time; like rslib v3 they
            // sit outside the review limit.
            learning.push(row);
        } else if row.is_new {
            new_cards.push(row);
        } else if row.due_day.is_none_or(|d| d <= today) {
            reviews.push(row);
        }
    }

    // A today-batch learning card has no due time because AnkiDroid already had it due.
    learning.sort_by_key(|r| r.due_at_secs.unwrap_or(i64::MIN));
    // Overdue and today's batch (due_day None) first, then by the order the phone listed them.
    reviews.sort_by(|a, b| match (a.due_day, b.due_day) {
        (None, None) => a.position.cmp(&b.position),
        (None, Some(_)) => Ordering::Less,
        (Some(_), None) => Ordering::Greater,
        (Some(x), Some(y)) => x.cmp(&y).then(a.position.cmp(&b.position)),
    });
    new_cards.sort_by_key(|r| r.position);

    if let Some(limit) = config.map(|c| i64::from(c.reviews_per_day).saturating_sub(reviews_graded))
    {
        reviews.truncate(limit as usize);
    }
    if let Some(limit) = config.map(|c| i64::from(c.new_per_day).saturating_sub(new_graded)) {
        new_cards.truncate(limit as usize);
    }

    let mut selected = learning;
    selected.extend(reviews);
    selected.extend(new_cards);
    selected
}

/// A card from today's batch that is in AnkiDroid's intraday learning queue.
fn is_intraday_learning(row: &PoolRow) -> bool {
    row.due_day.is_none()
        && row.card.state.as_ref().is_some_and(|s| s.queue == QUEUE_INTRADAY_LEARN)
}

/// Refresh a queued card's elapsed days and decide whether its shipped interval labels are still
/// usable. The phone's labels are exact only for an untouched today-batch card fetched the same
/// Anki day; everything else gets recomputed from the deck config and the card's state.
fn finalize_card(
    mut row: PoolRow,
    config: Option<&DeckScheduleConfig>,
    now_ms: i64,
    utc_offset_secs: i32,
    rollover_hour: i32,
    today: i64,
    fetched_day: i64,
) -> WearosCard {
    if let Some(state) = row.card.state.as_mut()
        && state.last_review_secs > 0
    {
        state.elapsed_days =
            elapsed_days_between(state.last_review_secs, now_ms, utc_offset_secs, rollover_hour)
                as f64;
    }

    let keep_shipped = row.card.due_day.is_none()
        && row.card.due_at_secs.is_none()
        && fetched_day == today
        && !row.graded;
    if !keep_shipped && let (Some(config), Some(state)) = (config, row.card.state.as_ref()) {
        row.card.next_review_times =
            preview_labels_for_buttons(config, state, now_ms, row.card.button_count);
    }
    row.card
}

/// How many grades of `kind` were logged for the deck on `day`.
fn graded_today(conn: &Connection, deck_id: i64, day: i64, kind: i64) -> Result<i64, CoreError> {
    Ok(conn.query_row(
        "SELECT COUNT(*) FROM grade_log WHERE deck_id = ?1 AND day = ?2 AND kind = ?3",
        params![deck_id, day, kind],
        |r| r.get(0),
    )?)
}

/// Count of learning cards due later today beyond the learn-ahead window, and the earliest such due
/// time. Learning cards pushed past the day's rollover belong to a future queue and are ignored.
fn learning_later_stats(
    conn: &Connection,
    deck_id: i64,
    now_ms: i64,
    utc_offset_secs: i32,
    rollover_hour: i32,
) -> Result<(i32, Option<i64>), CoreError> {
    let today = day_index(now_ms, utc_offset_secs, rollover_hour);
    let cutoff = now_ms.div_euclid(1_000) + LEARN_AHEAD_SECS;
    let mut stmt = conn.prepare(
        "SELECT due_at_secs FROM pool_cards
         WHERE deck_id = ?1 AND due_at_secs IS NOT NULL AND due_at_secs > ?2
         ORDER BY due_at_secs ASC",
    )?;
    let rows = stmt.query_map(params![deck_id, cutoff], |r| r.get::<_, i64>(0))?;

    let mut count = 0;
    let mut next = None;
    for secs in rows {
        let secs = secs?;
        if day_index(secs.saturating_mul(1_000), utc_offset_secs, rollover_hour) != today {
            continue;
        }
        count += 1;
        next = Some(next.map_or(secs, |n: i64| n.min(secs)));
    }
    Ok((count, next))
}

/// The full stored card, for the grading flow (crate-internal; not exported to Kotlin).
pub fn pool_card(card_id: i64) -> Result<Option<WearosCard>, CoreError> {
    let conn = get_db_connection()?;
    let blob: Option<Vec<u8>> = conn
        .query_row("SELECT card FROM pool_cards WHERE card_id = ?1", params![card_id], |r| r.get(0))
        .optional()?;
    blob.map(|b| decode_card(&b)).transpose()
}

/// A pooled card's full stored row, for the session's pool-mode undo (crate-internal).
pub struct PoolCardSnapshot {
    pub card: WearosCard,
    pub due_day: Option<i64>,
    pub due_at_secs: Option<i64>,
    pub graded: bool,
}

/// Read a card's stored row exactly as it is, so a grade can be reversed (crate-internal).
pub fn pool_card_snapshot(card_id: i64) -> Result<Option<PoolCardSnapshot>, CoreError> {
    let conn = get_db_connection()?;
    let row = conn
        .query_row(
            "SELECT card, due_day, due_at_secs, graded FROM pool_cards WHERE card_id = ?1",
            params![card_id],
            |r| {
                Ok((
                    r.get::<_, Vec<u8>>(0)?,
                    r.get::<_, Option<i64>>(1)?,
                    r.get::<_, Option<i64>>(2)?,
                    r.get::<_, i64>(3)? != 0,
                ))
            },
        )
        .optional()?;
    row.map(|(blob, due_day, due_at_secs, graded)| {
        Ok(PoolCardSnapshot { card: decode_card(&blob)?, due_day, due_at_secs, graded })
    })
    .transpose()
}

/// Write a card's new local state and due placement back to the pool (crate-internal).
///
/// `graded` marks it as locally changed so its shipped interval labels are not trusted again; a
/// graded card is never new, even if its new state still sits in the new queue.
pub fn pool_update_card(
    card: WearosCard,
    due_day: Option<i64>,
    due_at_secs: Option<i64>,
    graded: bool,
) -> Result<(), CoreError> {
    let conn = get_db_connection()?;
    let card_id = card.id;
    let is_new = !graded && card.state.as_ref().is_some_and(|s| s.queue == 0);
    let blob = encode_card(card);
    conn.execute(
        "UPDATE pool_cards
            SET card = ?2, due_day = ?3, due_at_secs = ?4, is_new = ?5, graded = ?6
          WHERE card_id = ?1",
        params![card_id, blob, due_day, due_at_secs, is_new, graded],
    )?;
    Ok(())
}

/// Log one grade for the daily limits, returning the row id so it can be undone (crate-internal).
pub fn pool_log_grade(deck_id: i64, card_id: i64, day: i64, kind: i64) -> Result<i64, CoreError> {
    let conn = get_db_connection()?;
    conn.execute(
        "INSERT INTO grade_log (deck_id, card_id, day, kind) VALUES (?1, ?2, ?3, ?4)",
        params![deck_id, card_id, day, kind],
    )?;
    Ok(conn.last_insert_rowid())
}

/// Remove a grade previously logged by [`pool_log_grade`] (crate-internal).
pub fn pool_unlog_grade(id: i64) -> Result<(), CoreError> {
    let conn = get_db_connection()?;
    conn.execute("DELETE FROM grade_log WHERE id = ?1", params![id])?;
    Ok(())
}
