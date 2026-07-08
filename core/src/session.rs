//! In-progress review session: tracks the current card, reveal state, and buffers each answer.

use crate::db::{get_db_connection, remove_buffered_answers, save_answer};
use crate::models::{CoreError, WearosAnswer, WearosCard, WearosDueBatch};
use crate::util::unix_millis;
use std::sync::Mutex;

/// End-of-session tally shown on the finished screen: how many cards were graded and how they broke
/// down by ease, plus the total time spent.
#[derive(uniffi::Record)]
pub struct SessionSummary {
    pub total: i32,
    pub again: i32,
    pub hard: i32,
    pub good: i32,
    pub easy: i32,
    pub total_time_ms: i64,
}

#[derive(uniffi::Object)]
pub struct ReviewSession {
    batch: WearosDueBatch,
    current_index: Mutex<usize>,
    is_revealed: Mutex<bool>,
    // The answers given so far, newest last — one per graded card. Lets undo pop the last one (and
    // delete it from the buffer so an accidental grade never reaches the phone) and backs the
    // end-of-session summary.
    graded: Mutex<Vec<WearosAnswer>>,
}

#[uniffi::export]
impl ReviewSession {
    #[uniffi::constructor]
    pub fn new(batch: WearosDueBatch) -> Self {
        Self {
            batch,
            current_index: Mutex::new(0),
            is_revealed: Mutex::new(false),
            graded: Mutex::new(Vec::new()),
        }
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
            let id = format!("{}_{}_{}", card.id, card.ord, unix_millis());
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

        // Record the answer so undo can pop + delete it and the summary can aggregate it.
        self.graded.lock().unwrap().push(answer.clone());

        {
            let mut revealed = self.is_revealed.lock().unwrap();
            *revealed = false;
        }

        Ok(Some(answer))
    }

    /// Whether there's a graded card to step back to.
    pub fn can_undo(&self) -> bool {
        *self.current_index.lock().unwrap() > 0
    }

    /// Undo the most recent grade: step the queue back one card, drop that answer from the local
    /// buffer (so it never reaches the phone), and re-show the card un-revealed. Returns the card
    /// that is now current again, or `None` if there was nothing to undo.
    pub fn undo_last_answer(&self) -> Result<Option<WearosCard>, CoreError> {
        {
            let mut index = self.current_index.lock().unwrap();
            if *index == 0 {
                return Ok(None);
            }
            *index -= 1;
        }

        // Pop the answer for the card we just stepped back onto and delete it from the buffer.
        if let Some(answer) = self.graded.lock().unwrap().pop()
            && get_db_connection().is_ok()
        {
            let _ = remove_buffered_answers(vec![answer.id]);
        }

        *self.is_revealed.lock().unwrap() = false;
        Ok(self.get_current_card())
    }

    pub fn get_progress_string(&self) -> String {
        let index = *self.current_index.lock().unwrap();
        format!("{}/{}", index, self.batch.cards.len())
    }

    /// Tally of everything graded so far this session (after undo, reflects the current state).
    pub fn get_summary(&self) -> SessionSummary {
        let mut s =
            SessionSummary { total: 0, again: 0, hard: 0, good: 0, easy: 0, total_time_ms: 0 };
        let graded = self.graded.lock().unwrap();
        s.total = graded.len() as i32;
        for a in graded.iter() {
            match a.ease {
                1 => s.again += 1,
                2 => s.hard += 1,
                3 => s.good += 1,
                _ => s.easy += 1, // ease 4 (and any out-of-range) counts as Easy
            }
            s.total_time_ms += a.time_taken_ms;
        }
        drop(graded); // release the lock before returning (clippy::significant_drop_tightening)
        s
    }
}
