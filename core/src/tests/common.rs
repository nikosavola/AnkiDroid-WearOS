//! Shared test fixtures: the global DB serialization lock and DB file cleanup. Every topic file
//! that drives the SQLite buffer pulls `db_test_guard` (or `no_db_guard`) through the tests module
//! root so the single DB_TEST_LOCK stays one static.

use std::sync::{Mutex, MutexGuard};

// The buffer tests all drive the single global DB_CONN, so serialize them; cargo runs tests in
// parallel by default. Recover from poisoning so one failing test doesn't cascade into the rest.
static DB_TEST_LOCK: Mutex<()> = Mutex::new(());
pub fn db_test_guard() -> MutexGuard<'static, ()> {
    DB_TEST_LOCK.lock().unwrap_or_else(std::sync::PoisonError::into_inner)
}

/// Guard for pure state-machine tests: locks the DB mutex AND clears the global path, so
/// answer_card skips buffering instead of inheriting (and now failing on) whatever stale DB a
/// previously-run test left in DB_CONN. save_answer failures are fatal since the durability fix.
pub fn no_db_guard() -> MutexGuard<'static, ()> {
    let guard = db_test_guard();
    *crate::db::DB_CONN.lock().unwrap() = None;
    guard
}

/// Remove a test DB plus its WAL sidecars, restoring write perms first (the read-only test
/// leaves the file unwritable if it panics before its own cleanup).
pub fn cleanup_db(path: &str) {
    use std::os::unix::fs::PermissionsExt;
    let _ = std::fs::set_permissions(path, std::fs::Permissions::from_mode(0o644));
    for suffix in ["", "-wal", "-shm"] {
        let _ = std::fs::remove_file(format!("{path}{suffix}"));
    }
}
