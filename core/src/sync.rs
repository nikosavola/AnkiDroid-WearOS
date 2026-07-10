//! Serialization protocol for the phone <-> watch Data Layer transfers.
//!
//! The due batch goes over the wire as protobuf, then zstd-compressed: card HTML is verbose and a
//! dense deck can blow past the Data Layer's ~100 KB DataItem limit and is slow over Bluetooth.
//! Protobuf is far more compact than JSON and zstd compresses this text-heavy data well; both live
//! here so the Kotlin sides just ship the bytes.
//!
//! Answers go over as protobuf too (no zstd: a buffered-answer list is tiny, so compression would
//! only add framing overhead). Everything on the wire is protobuf; nothing uses JSON.

use crate::models::{CoreError, WearosAnswer, WearosDueBatch};
use crate::proto;

/// zstd level for the due batch. The payloads are small and infrequent, so a high level is cheap
/// and maximizes the ratio (helps stay under the DataItem cap on dense decks).
const ZSTD_LEVEL: i32 = 19;

#[uniffi::export]
pub fn serialize_due_batch(batch: WearosDueBatch) -> Result<Vec<u8>, CoreError> {
    Ok(zstd::encode_all(proto::encode_due_batch(batch).as_slice(), ZSTD_LEVEL)?)
}

#[uniffi::export]
pub fn deserialize_due_batch(bytes: Vec<u8>) -> Result<WearosDueBatch, CoreError> {
    let proto_bytes = zstd::decode_all(bytes.as_slice())?;
    proto::decode_due_batch(&proto_bytes)
}

#[uniffi::export]
pub fn serialize_answers(answers: Vec<WearosAnswer>) -> Result<Vec<u8>, CoreError> {
    Ok(proto::encode_answers(answers))
}

#[uniffi::export]
pub fn deserialize_answers(bytes: Vec<u8>) -> Result<Vec<WearosAnswer>, CoreError> {
    proto::decode_answers(&bytes)
}
