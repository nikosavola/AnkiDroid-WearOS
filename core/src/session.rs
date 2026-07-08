//! In-progress review session: tracks the current card, reveal state, and buffers each answer.

use crate::db::{get_db_connection, save_answer};
use crate::models::{CoreError, WearosAnswer, WearosCard, WearosDueBatch};
use std::sync::Mutex;
use std::time::SystemTime;

#[derive(uniffi::Object)]
pub struct ReviewSession {
    batch: WearosDueBatch,
    current_index: Mutex<usize>,
    is_revealed: Mutex<bool>,
}

#[uniffi::export]
impl ReviewSession {
    #[uniffi::constructor]
    pub fn new(batch: WearosDueBatch) -> Self {
        Self { batch, current_index: Mutex::new(0), is_revealed: Mutex::new(false) }
    }

    pub fn get_deck_name(&self) -> String {
        self.batch.deck_name.clone()
    }

    pub fn get_total_cards(&self) -> i32 {
        self.batch.cards.len() as i32
    }

    pub fn get_current_index(&self) -> i32 {
        *self.current_index.lock().unwrap() as i32
    }

    pub fn is_revealed(&self) -> bool {
        *self.is_revealed.lock().unwrap()
    }

    pub fn get_current_card(&self) -> Option<WearosCard> {
        let index = *self.current_index.lock().unwrap();
        if index < self.batch.cards.len() { Some(self.batch.cards[index].clone()) } else { None }
    }

    pub fn reveal_answer(&self) {
        let mut revealed = self.is_revealed.lock().unwrap();
        *revealed = true;
    }

    pub fn answer_card(
        &self,
        ease: i32,
        time_taken_ms: i64,
    ) -> Result<Option<WearosAnswer>, CoreError> {
        let (card, id) = {
            let mut index = self.current_index.lock().unwrap();

            if *index >= self.batch.cards.len() {
                return Err(CoreError::StateError {
                    error_message: "Session is already finished.".to_string(),
                });
            }

            let card = &self.batch.cards[*index];
            let id = format!(
                "{}_{}_{}",
                card.id,
                card.ord,
                SystemTime::now().duration_since(SystemTime::UNIX_EPOCH).unwrap().as_millis()
            );
            let card_clone = card.clone();
            *index += 1;
            drop(index);
            (card_clone, id)
        };

        let answer = WearosAnswer { id, note_id: card.note_id, ord: card.ord, ease, time_taken_ms };

        // Attempt to buffer locally if database is configured
        if get_db_connection().is_ok() {
            let _ = save_answer(answer.clone());
        }

        {
            let mut revealed = self.is_revealed.lock().unwrap();
            *revealed = false;
        }

        Ok(Some(answer))
    }

    pub fn get_progress_string(&self) -> String {
        let index = *self.current_index.lock().unwrap();
        format!("{}/{}", index, self.batch.cards.len())
    }
}
