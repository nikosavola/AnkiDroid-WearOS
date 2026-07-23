//! In-progress review session.
//!
//! The queue is Anki-faithful rather than a one-shot list: grading a card whose chosen button lands
//! on a learning step (a sub-day interval like "1m"/"10m") sends the card back into the session so
//! you have to relearn it before the deck is finished, instead of silently marching past a lapse.
//! Cards whose chosen interval is a day or more graduate and leave the session.
//!
//! This is an offline-first approximation: every grade is still buffered and later written back to
//! AnkiDroid, which owns the real SM-2/FSRS schedule. The in-session requeue only decides whether a
//! card reappears now; it does not recompute intervals (we can't, offline), so a per-card cap forces
//! graduation after a few learning-step reappearances to guarantee the session terminates.

use crate::db::{get_db_connection, remove_buffered_answers, save_answer};
use crate::models::{CoreError, WearosAnswer, WearosCard, WearosDueBatch};
use crate::util::unix_millis;
use std::collections::VecDeque;
use std::sync::Mutex;
use std::sync::atomic::{AtomicU64, Ordering};

/// Process-lifetime sequence appended to every answer id. `unix_millis` alone collides when two
/// grades land in the same millisecond or the watch's clock steps backward (they resync constantly);
/// a colliding id would `INSERT OR REPLACE` an earlier buffered review out of existence.
static ANSWER_SEQ: AtomicU64 = AtomicU64::new(0);

/// Force a card to graduate after this many learning-step reappearances (excluding Again, which the
/// user is actively failing and may repeat freely). Bounds the session since we can't advance
/// through Anki's real learning steps offline; the true schedule is reconciled on write-back.
const MAX_LEARN_REQUEUES: u32 = 3;

/// End-of-session tally shown on the finished screen.
///
/// Counts reviews rather than distinct cards, so a card relearned within the session contributes
/// more than once. Breaks the total down by ease and sums the time spent.
#[derive(uniffi::Record)]
pub struct SessionSummary {
    pub total: i32,
    pub again: i32,
    pub hard: i32,
    pub good: i32,
    pub easy: i32,
    pub total_time_ms: i64,
}

/// A card in the queue plus how many learning-step requeues (not counting Again) it has taken, used
/// to enforce [`MAX_LEARN_REQUEUES`].
#[derive(Clone)]
struct QueuedCard {
    card: WearosCard,
    learn_requeues: u32,
}

/// One graded review, kept so undo can reverse it (LIFO) and the summary can aggregate.
struct GradeEvent {
    answer: WearosAnswer,
    /// The queue entry as it was at the front when graded, to push back on undo (restores the
    /// pre-grade `learn_requeues`).
    front_before: QueuedCard,
    /// Whether the grade requeued the card (pushed to the back) vs graduated it out.
    requeued: bool,
}

#[derive(uniffi::Object)]
pub struct ReviewSession {
    deck_name: String,
    /// The deck being reviewed, stamped onto each answer so the phone can select the right deck at
    /// write-back time even when a buffer spans several decks reviewed offline.
    deck_id: i64,
    /// Number of distinct due cards the session started with; the denominator of the progress string.
    initial_total: usize,
    /// Remaining cards, front = current. Learning-step grades push the card to the back.
    queue: Mutex<VecDeque<QueuedCard>>,
    is_revealed: Mutex<bool>,
    /// Cards that have permanently left the queue; the numerator of the progress string.
    graduated: Mutex<usize>,
    /// Every grade so far, newest last. Backs undo and the end-of-session summary.
    history: Mutex<Vec<GradeEvent>>,
    /// Serializes the two multi-step mutations (`answer_card` / `undo_last_answer`). They each touch
    /// `queue`, `history` and `graduated` under separate locks, so without this a grade+undo
    /// double-tap could interleave and pop the wrong tail entry. Always acquired first, released last.
    op_lock: Mutex<()>,
}

#[uniffi::export]
impl ReviewSession {
    #[uniffi::constructor]
    pub fn new(batch: WearosDueBatch) -> Self {
        let queue: VecDeque<QueuedCard> =
            batch.cards.into_iter().map(|card| QueuedCard { card, learn_requeues: 0 }).collect();
        Self {
            deck_name: batch.deck_name,
            deck_id: batch.deck_id,
            initial_total: queue.len(),
            queue: Mutex::new(queue),
            is_revealed: Mutex::new(false),
            graduated: Mutex::new(0),
            history: Mutex::new(Vec::new()),
            op_lock: Mutex::new(()),
        }
    }

    pub fn get_deck_name(&self) -> String {
        self.deck_name.clone()
    }

    /// The number of due cards the session started with (not the live remaining count).
    pub fn get_total_cards(&self) -> i32 {
        self.initial_total as i32
    }

    pub fn is_revealed(&self) -> bool {
        *self.is_revealed.lock().unwrap()
    }

    pub fn get_current_card(&self) -> Option<WearosCard> {
        self.queue.lock().unwrap().front().map(|qc| qc.card.clone())
    }

    pub fn reveal_answer(&self) {
        *self.is_revealed.lock().unwrap() = true;
    }

    pub fn answer_card(
        &self,
        ease: i32,
        time_taken_ms: i64,
    ) -> Result<Option<WearosAnswer>, CoreError> {
        let _op = self.op_lock.lock().unwrap();
        let front = {
            let mut queue = self.queue.lock().unwrap();
            match queue.pop_front() {
                Some(front) => front,
                None => {
                    return Err(CoreError::StateError {
                        error_message: "Session is already finished.".to_string(),
                    });
                }
            }
        };

        let card = &front.card;
        let seq = ANSWER_SEQ.fetch_add(1, Ordering::Relaxed);
        let id = format!("{}_{}_{}_{}", card.id, card.ord, unix_millis(), seq);
        let answer = WearosAnswer {
            id,
            note_id: card.note_id,
            ord: card.ord,
            ease,
            time_taken_ms,
            deck_id: self.deck_id,
        };

        // Buffer locally if a DB is configured (the answer still reaches AnkiDroid on sync). A grade
        // that can't be persisted must fail loudly, not vanish: put the card back at the front and
        // surface the error so the user re-grades, instead of the review silently never syncing.
        if get_db_connection().is_ok()
            && let Err(e) = save_answer(answer.clone())
        {
            self.queue.lock().unwrap().push_front(front);
            return Err(e);
        }

        // Again is always a relearn step and may repeat freely; other buttons requeue only while the
        // chosen interval is a learning step and the per-card cap hasn't been hit.
        let is_again = ease == 1;
        let requeue =
            is_again || (is_learning_step(card, ease) && front.learn_requeues < MAX_LEARN_REQUEUES);
        if requeue {
            let learn_requeues = front.learn_requeues + u32::from(!is_again);
            self.queue
                .lock()
                .unwrap()
                .push_back(QueuedCard { card: front.card.clone(), learn_requeues });
        } else {
            *self.graduated.lock().unwrap() += 1;
        }

        self.history.lock().unwrap().push(GradeEvent {
            answer: answer.clone(),
            front_before: front,
            requeued: requeue,
        });

        *self.is_revealed.lock().unwrap() = false;
        Ok(Some(answer))
    }

    /// Whether there's a graded review to step back to.
    pub fn can_undo(&self) -> bool {
        !self.history.lock().unwrap().is_empty()
    }

    /// Undo the most recent grade: reverse its queue effect (pull the requeued copy back off the tail
    /// or un-graduate), restore the card to the front un-revealed, and drop its buffered answer so it
    /// never reaches the phone. Returns the card that is current again, or `None` if nothing to undo.
    pub fn undo_last_answer(&self) -> Result<Option<WearosCard>, CoreError> {
        let _op = self.op_lock.lock().unwrap();
        let popped = self.history.lock().unwrap().pop();
        let Some(event) = popped else {
            return Ok(None);
        };

        // Drop the buffered answer first. If it can't be removed it may still sync to AnkiDroid and
        // reschedule the card, so fail loudly and leave the session exactly as it was (event back on
        // history, queue untouched) rather than showing an undo that didn't really take. Symmetric
        // with answer_card's loud-failure contract.
        if get_db_connection().is_ok()
            && let Err(e) = remove_buffered_answers(vec![event.answer.id.clone()])
        {
            self.history.lock().unwrap().push(event);
            return Err(e);
        }

        {
            let mut queue = self.queue.lock().unwrap();
            if event.requeued {
                // The copy we pushed on grade is the newest tail entry.
                queue.pop_back();
            }
            queue.push_front(event.front_before);
        }
        if !event.requeued {
            let mut graduated = self.graduated.lock().unwrap();
            *graduated = graduated.saturating_sub(1);
        }

        *self.is_revealed.lock().unwrap() = false;
        Ok(self.get_current_card())
    }

    /// Progress as graduated-of-initial. Because relearning cards stay in the queue, this only
    /// reaches N/N once every lapse has actually been relearned out of the session.
    pub fn get_progress_string(&self) -> String {
        format!("{}/{}", *self.graduated.lock().unwrap(), self.initial_total)
    }

    /// Graduated-of-initial as a fraction in [0.0, 1.0], for the watch's circular progress ring
    /// (get_progress_string gives the matching "N/M" label). 0.0 when the session started empty.
    pub fn get_progress_fraction(&self) -> f32 {
        if self.initial_total == 0 {
            return 0.0;
        }
        *self.graduated.lock().unwrap() as f32 / self.initial_total as f32
    }

    /// Tally of every review graded so far this session (after undo, reflects the current state).
    pub fn get_summary(&self) -> SessionSummary {
        let mut s =
            SessionSummary { total: 0, again: 0, hard: 0, good: 0, easy: 0, total_time_ms: 0 };
        let history = self.history.lock().unwrap();
        s.total = history.len() as i32;
        for e in history.iter() {
            match e.answer.ease {
                1 => s.again += 1,
                2 => s.hard += 1,
                3 => s.good += 1,
                _ => s.easy += 1, // ease 4 (and any out-of-range) counts as Easy
            }
            s.total_time_ms += e.answer.time_taken_ms;
        }
        drop(history); // release the lock before returning (clippy::significant_drop_tightening)
        s
    }
}

/// The interval label AnkiDroid computed for the button `ease` selects, e.g. "10m" or "4d". Maps the
/// Anki ease (1..=4) to the right slot for 3- and 4-button cards (3-button has no Hard: Again/Good/
/// Easy), clamping defensively. `None` when the card carries no interval labels.
fn interval_label(card: &WearosCard, ease: i32) -> Option<&str> {
    if card.next_review_times.is_empty() {
        return None;
    }
    let idx = match card.button_count {
        n if n >= 4 => (ease - 1).max(0) as usize,
        3 => match ease {
            1 => 0, // Again
            4 => 2, // Easy
            _ => 1, // Good (ease 3), and any stray value
        },
        2 => usize::from(ease > 1),
        _ => 0,
    };
    let idx = idx.min(card.next_review_times.len() - 1);
    card.next_review_times.get(idx).map(String::as_str)
}

/// Whether the chosen button lands on a learning step (a sub-day interval), meaning the card should
/// reappear this session rather than graduate out. With no interval data, only Again reappears.
fn is_learning_step(card: &WearosCard, ease: i32) -> bool {
    interval_label(card, ease).map_or(ease == 1, label_is_subday)
}

/// True for sub-day interval units (seconds/minutes/hours), false for day/month/year. Labels look
/// like "45s", "1m", "2h", "4d", "3mo", "1y"; note "m" is minutes but "mo" is months.
fn label_is_subday(label: &str) -> bool {
    let unit: String = label.chars().filter(char::is_ascii_alphabetic).collect();
    matches!(unit.as_str(), "s" | "m" | "h")
}
