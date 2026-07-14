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
use std::io::Read;

/// zstd level for the due batch. The payloads are small and infrequent, so a high level is cheap
/// and maximizes the ratio (helps stay under the DataItem cap on dense decks).
const ZSTD_LEVEL: i32 = 19;

/// Cap on the decompressed due-batch size. Real batches are a few hundred KB at most (<=20 cards of
/// stripped HTML), but zstd honours whatever size the frame *declares*, so a corrupt or hostile
/// payload could otherwise balloon to gigabytes and OOM the watch. Generous headroom over any
/// legitimate batch.
const MAX_DECOMPRESSED_BYTES: u64 = 32 * 1024 * 1024;

#[uniffi::export]
pub fn serialize_due_batch(batch: WearosDueBatch) -> Result<Vec<u8>, CoreError> {
    Ok(zstd::encode_all(proto::encode_due_batch(batch).as_slice(), ZSTD_LEVEL)?)
}

#[uniffi::export]
pub fn deserialize_due_batch(bytes: Vec<u8>) -> Result<WearosDueBatch, CoreError> {
    // Stream-decode with a hard ceiling instead of decode_all, so the cap is enforced while
    // decompressing rather than after the allocation already happened.
    let mut proto_bytes = Vec::new();
    let read = zstd::Decoder::new(bytes.as_slice())?
        .take(MAX_DECOMPRESSED_BYTES + 1)
        .read_to_end(&mut proto_bytes)?;
    if read as u64 > MAX_DECOMPRESSED_BYTES {
        return Err(CoreError::SerializationError {
            error_message: format!(
                "due batch decompresses past the {MAX_DECOMPRESSED_BYTES}-byte cap; refusing"
            ),
        });
    }
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
