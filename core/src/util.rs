//! Small shared helpers.

// These are crate-internal (pub(crate)) in a private module, which the nursery lint flags as
// redundant; that's the intended shape for helpers shared across sibling modules.
#![expect(clippy::redundant_pub_crate, reason = "shared crate-internal helpers")]

use std::time::{SystemTime, UNIX_EPOCH};

/// Whole seconds since the Unix epoch (0 if the clock is somehow set before it, rather than
/// panicking on the `SystemTimeError`).
pub(crate) fn unix_secs() -> i64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map_or(0, |d| d.as_secs() as i64)
}

/// Milliseconds since the Unix epoch (used to build unique answer ids).
pub(crate) fn unix_millis() -> u128 {
    SystemTime::now().duration_since(UNIX_EPOCH).map_or(0, |d| d.as_millis())
}
