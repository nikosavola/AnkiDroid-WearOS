//! wearos-core: the shared Rust core behind the phone and watch apps. All logic (models, the
//! answer buffer, sync serialization, the review state machine, card-HTML sanitizing, and deck
//! hierarchy) lives in the submodules below and is exported to Kotlin via UniFFI.

uniffi::setup_scaffolding!();

mod css;
mod day;
mod db;
mod decks;
mod html;
mod models;
mod pool;
mod proto;
mod sched;
mod session;
mod sync;
mod tile;
mod typeanswer;
mod util;

// Flatten the crate's Rust surface so tests and any internal callers see everything at the root.
// (UniFFI records each #[uniffi::export] at its definition site, so the module layout doesn't
// affect the generated Kotlin API.)
// db is re-exported by name rather than with `*` so its crate-internal DB_CONN/get_db_connection
// stay unexported (reached via `crate::db::...`) — that keeps them plain `pub` in a private module
// without tripping `redundant_pub_crate`.
pub use css::*;
pub use day::*;
pub use db::{
    BufferStats, average_answer_time_ms, buffered_answer_stats, get_buffered_answers,
    init_database, record_unplaced_answers, remove_buffered_answers, reviews_since, save_answer,
};
pub use decks::*;
pub use html::*;
pub use models::*;
pub use pool::*;
pub use sched::*;
pub use session::*;
pub use sync::*;
pub use tile::*;
pub use typeanswer::*;

#[cfg(test)]
mod tests;
