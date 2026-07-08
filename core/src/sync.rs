//! JSON serialization protocol for the phone <-> watch Data Layer transfers.

use crate::models::{CoreError, WearosAnswer, WearosDueBatch};

#[uniffi::export]
pub fn serialize_due_batch(batch: WearosDueBatch) -> Result<Vec<u8>, CoreError> {
    serde_json::to_vec(&batch)
        .map_err(|e| CoreError::SerializationError { error_message: e.to_string() })
}

#[uniffi::export]
pub fn deserialize_due_batch(bytes: Vec<u8>) -> Result<WearosDueBatch, CoreError> {
    serde_json::from_slice(&bytes)
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
