//! Serialization protocol for the phone <-> watch Data Layer transfers.
//!
//! The due-batch payload is JSON, then gzipped: card HTML is verbose and a dense deck can blow past
//! the Data Layer's ~100 KB DataItem limit and is slow over Bluetooth. Folding gzip in here keeps
//! the compression contract in one place (the Kotlin sides just ship the bytes), and
//! [`deserialize_due_batch`] sniffs the gzip magic so it still reads pre-compression (raw-JSON)
//! items a not-yet-upgraded peer may have left on the data layer.
//!
//! Answers stay raw JSON: a buffered-answer list is tiny, so compressing it isn't worth the extra
//! coordinated change on both apps.

use crate::models::{CoreError, WearosAnswer, WearosDueBatch};
use flate2::Compression;
use flate2::read::GzDecoder;
use flate2::write::GzEncoder;
use std::io::{Read, Write};

/// Two-byte gzip header (RFC 1952). Present iff the bytes are gzip-compressed.
const GZIP_MAGIC: [u8; 2] = [0x1f, 0x8b];

fn gzip(bytes: &[u8]) -> Result<Vec<u8>, CoreError> {
    let mut encoder = GzEncoder::new(Vec::new(), Compression::default());
    encoder
        .write_all(bytes)
        .and_then(|()| encoder.finish())
        .map_err(|e| CoreError::SerializationError { error_message: e.to_string() })
}

/// Inflate `bytes` when gzip-framed, otherwise return them unchanged (backward compatibility with
/// payloads produced before compression moved into the core).
fn maybe_gunzip(bytes: Vec<u8>) -> Result<Vec<u8>, CoreError> {
    if bytes.len() >= 2 && bytes[..2] == GZIP_MAGIC {
        let mut out = Vec::new();
        GzDecoder::new(&bytes[..])
            .read_to_end(&mut out)
            .map_err(|e| CoreError::SerializationError { error_message: e.to_string() })?;
        Ok(out)
    } else {
        Ok(bytes)
    }
}

#[uniffi::export]
pub fn serialize_due_batch(batch: WearosDueBatch) -> Result<Vec<u8>, CoreError> {
    let json = serde_json::to_vec(&batch)
        .map_err(|e| CoreError::SerializationError { error_message: e.to_string() })?;
    gzip(&json)
}

#[uniffi::export]
pub fn deserialize_due_batch(bytes: Vec<u8>) -> Result<WearosDueBatch, CoreError> {
    let json = maybe_gunzip(bytes)?;
    serde_json::from_slice(&json)
        .map_err(|e| CoreError::SerializationError { error_message: e.to_string() })
}

#[uniffi::export]
pub fn serialize_answers(answers: Vec<WearosAnswer>) -> Result<Vec<u8>, CoreError> {
    serde_json::to_vec(&answers)
        .map_err(|e| CoreError::SerializationError { error_message: e.to_string() })
}

#[uniffi::export]
pub fn deserialize_answers(bytes: Vec<u8>) -> Result<Vec<WearosAnswer>, CoreError> {
    serde_json::from_slice(&bytes)
        .map_err(|e| CoreError::SerializationError { error_message: e.to_string() })
}
