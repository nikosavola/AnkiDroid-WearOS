//! Anki-style day numbering, shared by the phone and the watch so both agree on when "today"
//! starts. Anki rolls the day over at a configurable hour (default 4am local time), not at midnight,
//! and elapsed time for FSRS/SM-2 is counted in those day numbers rather than wall-clock fractions.

/// Anki's default "next day starts at" hour (4am local time).
pub const DEFAULT_ROLLOVER_HOUR: i32 = 4;

/// Anki's default rollover hour. UniFFI cannot export consts, so this shim carries it across.
#[uniffi::export]
pub fn default_rollover_hour() -> i32 {
    DEFAULT_ROLLOVER_HOUR
}

/// Anki-style day number: days since the Unix epoch in local time, with the day starting at
/// `rollover_hour`.
///
/// `unix_ms` is shifted into local time by `utc_offset_secs`, then back by the rollover hour, and
/// floored to whole days. `div_euclid` keeps the floor correct for times before the epoch and for
/// negative offsets, where truncating division would round toward zero.
#[uniffi::export]
pub fn day_index(unix_ms: i64, utc_offset_secs: i32, rollover_hour: i32) -> i64 {
    let local_secs = unix_ms.div_euclid(1_000) + i64::from(utc_offset_secs)
        - i64::from(rollover_hour.clamp(0, 23)) * 3_600;
    local_secs.div_euclid(86_400)
}

/// Whole Anki days between the card's last review and now, counted in the same rollover-based day
/// numbers. Never negative: a clock that is behind the last review reads as zero elapsed days.
#[uniffi::export]
pub fn elapsed_days_between(
    last_review_secs: i64,
    now_ms: i64,
    utc_offset_secs: i32,
    rollover_hour: i32,
) -> i64 {
    let last_review_ms = last_review_secs.saturating_mul(1_000);
    (day_index(now_ms, utc_offset_secs, rollover_hour)
        - day_index(last_review_ms, utc_offset_secs, rollover_hour))
    .max(0)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// 2026-10-07 00:00:00 UTC as epoch seconds, derived from the epoch day count.
    const OCT_7_2026_UTC_SECS: i64 = 1_791_331_200;
    const HELSINKI_SUMMER_OFFSET: i32 = 3 * 3_600;
    const PACIFIC_OFFSET: i32 = -7 * 3_600;

    fn local_ms(day_secs: i64, offset_secs: i32, hour: i64, minute: i64) -> i64 {
        (day_secs - i64::from(offset_secs) + hour * 3_600 + minute * 60) * 1_000
    }

    /// Anki day number for 2026-10-07 with the default rollover.
    const OCT_7_2026_ANKI_DAY: i64 = 20_733;

    #[test]
    fn test_day_index_rolls_over_at_the_configured_hour() {
        // 03:59 local is still the previous Anki day; 04:00 starts the new one, for every offset.
        for offset in [0, HELSINKI_SUMMER_OFFSET, PACIFIC_OFFSET] {
            let just_before = local_ms(OCT_7_2026_UTC_SECS, offset, 3, 59);
            let at_rollover = local_ms(OCT_7_2026_UTC_SECS, offset, 4, 0);
            assert_eq!(
                day_index(at_rollover, offset, DEFAULT_ROLLOVER_HOUR),
                OCT_7_2026_ANKI_DAY,
                "04:00 local is the new Anki day at offset {offset}"
            );
            assert_eq!(
                day_index(just_before, offset, DEFAULT_ROLLOVER_HOUR),
                OCT_7_2026_ANKI_DAY - 1,
                "03:59 local is the previous Anki day at offset {offset}"
            );
        }
    }

    #[test]
    fn test_midnight_rollover_matches_the_plain_local_date() {
        // With a rollover of 0 the Anki day is just the local calendar date.
        let ms = local_ms(OCT_7_2026_UTC_SECS, HELSINKI_SUMMER_OFFSET, 0, 30);
        assert_eq!(day_index(ms, HELSINKI_SUMMER_OFFSET, 0), OCT_7_2026_ANKI_DAY);
    }

    #[test]
    fn test_day_index_floors_before_the_epoch() {
        // One millisecond before 1970-01-01 is the previous day, not day zero.
        assert_eq!(day_index(-1, 0, 0), -1);
        // A negative offset pushes an early-epoch instant further back, still flooring correctly.
        assert_eq!(day_index(0, PACIFIC_OFFSET, 0), -1);
    }

    #[test]
    fn test_elapsed_days_between_within_one_anki_day_is_zero() {
        let morning = local_ms(OCT_7_2026_UTC_SECS, 0, 5, 0) / 1_000;
        let evening = local_ms(OCT_7_2026_UTC_SECS, 0, 20, 0);
        assert_eq!(
            elapsed_days_between(morning, evening, 0, DEFAULT_ROLLOVER_HOUR),
            0,
            "both instants fall on the same Anki day"
        );
    }

    #[test]
    fn test_elapsed_days_between_across_a_rollover_is_one() {
        // Reviewed at 03:00 (previous Anki day), now 05:00 (today): one day elapsed.
        let last = local_ms(OCT_7_2026_UTC_SECS, 0, 3, 0) / 1_000;
        let now = local_ms(OCT_7_2026_UTC_SECS, 0, 5, 0);
        assert_eq!(elapsed_days_between(last, now, 0, DEFAULT_ROLLOVER_HOUR), 1);
    }

    #[test]
    fn test_elapsed_days_between_is_never_negative() {
        // A clock behind the last review must not yield negative elapsed days.
        let last = local_ms(OCT_7_2026_UTC_SECS, 0, 9, 0) / 1_000;
        let earlier = local_ms(OCT_7_2026_UTC_SECS, 0, 8, 0);
        assert_eq!(elapsed_days_between(last, earlier, 0, DEFAULT_ROLLOVER_HOUR), 0);
    }
}
