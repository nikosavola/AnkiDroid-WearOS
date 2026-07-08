//! Async FFI surface (UniFFI maps Rust `async fn` -> Kotlin `suspend fun`).
//!
//! This is the seed of the "do heavy work in Rust off the main thread, consume with coroutines"
//! direction in the backlog: instead of wrapping a blocking FFI call in
//! `withContext(Dispatchers.IO)` on the Kotlin side, expose the work as a suspend function here.
//!
//! CAVEAT: a plain `async fn` with no `.await` yield point (like the ones below) is polled to
//! completion by the binding's async machinery, so the CPU work still runs on whatever thread
//! drives the poll — it does not magically move CPU-bound work off-thread. To genuinely offload
//! blocking/CPU work, add the `tokio` feature to the `uniffi` dependency and annotate with
//! `#[uniffi::export(async_runtime = "tokio")]`, then run the body inside
//! `tokio::task::spawn_blocking(...)`. The functions here deliberately stay runtime-free so the
//! crate keeps building without pulling a runtime; they exist to prove the suspend binding shape
//! and give the Kotlin side something to migrate onto incrementally.

use crate::models::{CoreError, WearosDueBatch};

/// Async twin of [`crate::deserialize_due_batch`]. Kotlin sees `suspend fun deserializeDueBatchAsync`
/// and can call it directly from a coroutine instead of `withContext(Dispatchers.IO) { ... }`.
// No `.await`: see the module note — a real runtime (tokio + spawn_blocking) is what would add the
// yield point; this proves the binding shape.
#[expect(clippy::unused_async, reason = "runtime-free async seed; body is sync until tokio lands")]
#[uniffi::export]
pub async fn deserialize_due_batch_async(bytes: Vec<u8>) -> Result<WearosDueBatch, CoreError> {
    crate::deserialize_due_batch(bytes)
}

/// Async twin of [`crate::serialize_due_batch`].
#[expect(clippy::unused_async, reason = "runtime-free async seed; body is sync until tokio lands")]
#[uniffi::export]
pub async fn serialize_due_batch_async(batch: WearosDueBatch) -> Result<Vec<u8>, CoreError> {
    crate::serialize_due_batch(batch)
}
