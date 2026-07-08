//! Small shared helpers. `pub` (not `pub(crate)`) inside this private, un-re-exported module so
//! sibling modules can use them while they stay crate-internal — and without `redundant_pub_crate`.

use std::time::{SystemTime, UNIX_EPOCH};

/// Whole seconds since the Unix epoch (0 if the clock is somehow set before it, rather than
/// panicking on the `SystemTimeError`).
pub fn unix_secs() -> i64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map_or(0, |d| d.as_secs() as i64)
}

/// Milliseconds since the Unix epoch (used to build unique answer ids).
pub fn unix_millis() -> u128 {
    SystemTime::now().duration_since(UNIX_EPOCH).map_or(0, |d| d.as_millis())
}
