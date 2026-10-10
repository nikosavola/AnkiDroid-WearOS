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

/// The value of a tag's `attr` attribute, quoted or not, or `None` when absent. [inner] is the
/// tag's inside (`<` to `>`); the search runs lowercased, the value is read from the original.
pub fn attr_value<'a>(inner: &'a str, attr: &str) -> Option<&'a str> {
    let lower = inner.to_ascii_lowercase();
    let mut search = 0;
    while let Some(found) = lower[search..].find(attr) {
        let at = search + found;
        search = at + attr.len();
        let preceded = at > 0 && lower.as_bytes()[at - 1].is_ascii_whitespace();
        let after = lower[at + attr.len()..].trim_start();
        if !preceded || !after.starts_with('=') {
            continue;
        }
        let value_start = inner.len() - after.len() + 1;
        let value = inner[value_start..].trim_start();
        return match value.chars().next()? {
            q @ ('"' | '\'') => value[1..].split(q).next(),
            _ => value.split(|c: char| c.is_whitespace() || c == '/').next(),
        };
    }
    None
}
