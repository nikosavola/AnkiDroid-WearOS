//! Glanceable Tile helpers: turning the due-card count plus the user's observed answer pace into a
//! human "time to clear the queue" string. Kept in Rust so the Tile and any future complication
//! share one estimate, per the crate's logic-in-Rust convention.

/// Flat fallback used before we have any observed answer times to average.
const DEFAULT_SECS_PER_CARD: f64 = 10.0;

/// Format the Tile's ETA line.
///
/// `avg_ms_per_card` is the mean answer time from the local buffer (see
/// [`crate::average_answer_time_ms`]) when known; a missing or non-positive value falls back to a
/// flat [`DEFAULT_SECS_PER_CARD`] estimate. `card_count` <= 0 means the queue is clear.
#[uniffi::export]
pub fn format_queue_eta(card_count: i32, avg_ms_per_card: Option<i64>) -> String {
    if card_count <= 0 {
        return "All caught up 🎉".to_string();
    }

    let per_card_secs =
        avg_ms_per_card.filter(|&ms| ms > 0).map_or(DEFAULT_SECS_PER_CARD, |ms| ms as f64 / 1000.0);
    let total_secs = (f64::from(card_count) * per_card_secs).round() as i64;

    if total_secs < 60 {
        format!("~{total_secs}s to finish")
    } else {
        // Round up to whole minutes so a queue that needs 61s doesn't read as "~1 min" too rosily.
        format!("~{} min to finish", (total_secs + 59) / 60)
    }
}
