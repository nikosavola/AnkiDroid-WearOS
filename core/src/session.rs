//! In-progress review session.
//!
//! The queue is Anki-faithful rather than a one-shot list: grading a card whose chosen button lands
//! on a learning step (a sub-day interval like "1m"/"10m") sends the card back into the session so
//! you have to relearn it before the deck is finished, instead of silently marching past a lapse.
//! Cards whose chosen interval is a day or more graduate and leave the session.
//!
//! This is an offline-first approximation: every grade is still buffered and later written back to
//! AnkiDroid, which owns the real SM-2/FSRS schedule. When the phone shipped the deck's scheduling
//! config, a requeued card's state is advanced and its interval previews are recomputed locally
//! (see `sched`), so the card reappears with the numbers for the state it is actually in rather than
//! the ones fetched before the grade. Without that config the labels stay as fetched, and a per-card
//! cap still forces graduation so the session terminates either way.

use crate::day::day_index;
use crate::db::{get_db_connection, remove_buffered_answers, save_answer};
use crate::models::{CoreError, WearosAnswer, WearosCard, WearosDueBatch};
use crate::pool::{
    GRADE_KIND_LEARNING, GRADE_KIND_NEW, GRADE_KIND_REVIEW, LEARN_AHEAD_SECS, PoolCardSnapshot,
    pool_card_snapshot, pool_log_grade, pool_today_batch, pool_unlog_grade, pool_update_card,
};
use crate::sched::{
    CardScheduleState, DeckScheduleConfig, SECS_PER_DAY, advance_state, preview_interval_secs,
    preview_labels_for_buttons,
};
use crate::util::unix_millis;
use std::collections::VecDeque;
use std::sync::Mutex;
use std::sync::atomic::{AtomicU64, Ordering};

/// Process-lifetime sequence appended to every answer id. `unix_millis` alone collides when two
/// grades land in the same millisecond or the watch's clock steps backward (they resync constantly);
/// a colliding id would `INSERT OR REPLACE` an earlier buffered review out of existence.
static ANSWER_SEQ: AtomicU64 = AtomicU64::new(0);

/// Force a card to graduate after this many learning-step reappearances (excluding Again, which the
/// user is actively failing and may repeat freely).
///
/// Anki has no such cap: a card stays in learning until the user answers it out of the ladder. On a
/// watch that would let a session run forever (pressing Hard at the last step repeats it, and Again
/// always repeats), so the session keeps a bound. Without the deck's step list this constant *is*
/// the ladder; with it, the bound is set past the step count ([`ReviewSession::requeue_cap`]) and
/// only bites when a user repeats Hard indefinitely - a deliberate deviation from Anki in favour of
/// a session that always ends.
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

/// What a pool-backed session needs to reschedule grades offline: the day boundary the pool was
/// built with. Present only for [`ReviewSession::from_pool`]; a batch-built session leaves it None
/// and keeps the old label-driven behaviour.
struct PoolContext {
    utc_offset_secs: i32,
    rollover_hour: i32,
}

/// The stored pool row a grade overwrote, so undo can put it back exactly (plus the grade_log row
/// to drop). Captured before the grade, since the grade mutates both.
struct PoolUndo {
    snapshot: PoolCardSnapshot,
    grade_log_id: i64,
}

/// What [`ReviewSession::reschedule_in_pool`] wrote and what the session needs to requeue the card.
struct PoolGradeOutcome {
    stored_card: WearosCard,
    due_at_secs: Option<i64>,
    now_secs: i64,
    undo: PoolUndo,
}

/// One graded review, kept so undo can reverse it (LIFO) and the summary can aggregate.
struct GradeEvent {
    answer: WearosAnswer,
    /// The queue entry as it was at the front when graded, to push back on undo (restores the
    /// pre-grade `learn_requeues`).
    front_before: QueuedCard,
    /// Whether the grade requeued the card (pushed to the back) vs graduated it out.
    requeued: bool,
    /// The pool row and grade_log this grade replaced, when grading in pool mode.
    pool_undo: Option<PoolUndo>,
}

/// Anki shows a learning card ahead of its due time (inside the learn-ahead window) only once
/// nothing else is left. Requeued learning copies sit in due order ahead of the new and review cards
/// (`due_at_secs` is None), so when the front one isn't due yet, the first of those goes first.
fn front_due_card(queue: &mut VecDeque<QueuedCard>, now_secs: i64) {
    let waiting = queue.front().and_then(|qc| qc.card.due_at_secs).is_some_and(|at| at > now_secs);
    if waiting
        && let Some(pos) = queue.iter().position(|qc| qc.card.due_at_secs.is_none())
        && let Some(card) = queue.remove(pos)
    {
        queue.push_front(card);
    }
}

#[derive(uniffi::Object)]
pub struct ReviewSession {
    deck_name: String,
    /// The deck being reviewed, stamped onto each answer so the phone can select the right deck at
    /// write-back time even when a buffer spans several decks reviewed offline.
    deck_id: i64,
    /// Number of distinct due cards the session started with; the denominator of the progress string.
    initial_total: usize,
    /// The deck's scheduling config, when the phone shipped it: drives the local recompute of a
    /// requeued card's previews. `None` leaves the fetched labels alone.
    schedule_config: Option<DeckScheduleConfig>,
    /// Anki's "next day starts at" hour from the batch, so a reopened deck keeps the same day
    /// boundary the phone used.
    rollover_hour: i32,
    /// When the phone fetched the batch, epoch ms; 0 = unknown. Carried so a persisted session keeps
    /// the pool's age.
    fetched_at_ms: i64,
    /// Set for a pool-backed session ([`ReviewSession::from_pool`]): grades then reschedule the card
    /// in the pool too, not just in the buffer.
    pool: Option<PoolContext>,
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
            schedule_config: batch.schedule_config,
            rollover_hour: batch.rollover_hour,
            fetched_at_ms: batch.fetched_at_ms,
            pool: None,
            initial_total: queue.len(),
            queue: Mutex::new(queue),
            is_revealed: Mutex::new(false),
            graduated: Mutex::new(0),
            history: Mutex::new(Vec::new()),
            op_lock: Mutex::new(()),
        }
    }

    /// Build a session for a deck the watch has pooled, queuing today's batch. Unlike [`Self::new`],
    /// grading also reschedules the card in the pool (new due day / learning due time) so it comes
    /// back on the right day without the phone, and undo reverses that.
    #[uniffi::constructor]
    pub fn from_pool(deck_id: i64, now_ms: i64, utc_offset_secs: i32) -> Result<Self, CoreError> {
        let batch = pool_today_batch(deck_id, now_ms, utc_offset_secs)?;
        let rollover_hour = batch.rollover_hour;
        let mut session = Self::new(batch);
        session.pool = Some(PoolContext { utc_offset_secs, rollover_hour });
        front_due_card(&mut session.queue.lock().unwrap(), now_ms.div_euclid(1_000));
        Ok(session)
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

    /// Ids of up to `count` cards queued after the current one, so the watch can warm their images.
    pub fn upcoming_card_ids(&self, count: u32) -> Vec<i64> {
        self.queue
            .lock()
            .unwrap()
            .iter()
            .skip(1)
            .take(count as usize)
            .map(|qc| qc.card.id)
            .collect()
    }

    /// The cards still to review, front first (requeued learning cards included with their refreshed
    /// previews/state). The watch persists this so a reopened deck resumes where the user left off.
    pub fn remaining_batch(&self) -> WearosDueBatch {
        let _op = self.op_lock.lock().unwrap();
        let cards = self.queue.lock().unwrap().iter().map(|qc| qc.card.clone()).collect();
        WearosDueBatch {
            deck_id: self.deck_id,
            deck_name: self.deck_name.clone(),
            cards,
            schedule_config: self.schedule_config.clone(),
            rollover_hour: self.rollover_hour,
            fetched_at_ms: self.fetched_at_ms,
        }
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

        // Pool mode additionally reschedules the card offline and logs the grade for the daily
        // limits. That write shares save_answer's loud-failure contract: a failure rolls the buffered
        // answer back and re-fronts the card rather than leaving the pool and the session disagreeing.
        let pool_grade = match &self.pool {
            Some(pool) => match self.reschedule_in_pool(card, ease, pool) {
                Ok(outcome) => Some(outcome),
                Err(e) => {
                    if get_db_connection().is_ok() {
                        let _ = remove_buffered_answers(vec![answer.id]);
                    }
                    self.queue.lock().unwrap().push_front(front);
                    return Err(e);
                }
            },
            None => None,
        };

        let requeued = self.requeue_or_graduate(&front, card, ease, pool_grade.as_ref());
        if let Some(outcome) = &pool_grade {
            front_due_card(&mut self.queue.lock().unwrap(), outcome.now_secs);
        }

        self.history.lock().unwrap().push(GradeEvent {
            answer: answer.clone(),
            front_before: front,
            requeued,
            pool_undo: pool_grade.map(|o| o.undo),
        });

        *self.is_revealed.lock().unwrap() = false;
        Ok(Some(answer))
    }
}

/// Session-internal helpers, kept out of the `#[uniffi::export]` impl so the macro never tries to
/// lift/lower the private types (`QueuedCard`, `PoolContext`, `PoolGradeOutcome`) they traffic in.
impl ReviewSession {
    /// Put the graded card back in the session or let it graduate, returning whether it requeued.
    ///
    /// Again is always a relearn step and may repeat freely; other buttons requeue only while the
    /// chosen interval is a learning step and the per-card cap hasn't been hit. In pool mode the
    /// card's freshly scheduled due time (within the learn-ahead window) drives that instead, with
    /// the same per-card cap bounding an endless Hard. A pool-mode copy is slotted by due time so it
    /// comes back in the right order; cards with no due time (review/new) sort last, and
    /// [`front_due_card`] lets them go first while the copy at the front isn't due yet.
    fn requeue_or_graduate(
        &self,
        front: &QueuedCard,
        card: &WearosCard,
        ease: i32,
        pool_grade: Option<&PoolGradeOutcome>,
    ) -> bool {
        let is_again = ease == 1;
        let requeue = pool_grade.map_or_else(
            || {
                is_again
                    || (self.is_learning_step(card, ease)
                        && front.learn_requeues < self.requeue_cap())
            },
            |outcome| {
                outcome.due_at_secs.is_some_and(|at| at <= outcome.now_secs + LEARN_AHEAD_SECS)
                    && front.learn_requeues < self.requeue_cap()
            },
        );
        if !requeue {
            *self.graduated.lock().unwrap() += 1;
            return false;
        }

        let next_card = pool_grade.map_or_else(
            || self.refresh_previews(&front.card, ease),
            |outcome| outcome.stored_card.clone(),
        );
        let entry = QueuedCard {
            card: next_card,
            learn_requeues: front.learn_requeues + u32::from(!is_again),
        };
        match pool_grade {
            Some(outcome) => {
                let due_at = outcome.due_at_secs.unwrap_or(i64::MAX);
                let mut queue = self.queue.lock().unwrap();
                match queue.iter().position(|qc| qc.card.due_at_secs.unwrap_or(i64::MAX) > due_at) {
                    Some(pos) => queue.insert(pos, entry),
                    None => queue.push_back(entry),
                }
            }
            None => self.queue.lock().unwrap().push_back(entry),
        }
        true
    }

    /// Reschedule one graded card in the pool and log the grade, returning what the session needs to
    /// requeue or (on undo) restore it.
    fn reschedule_in_pool(
        &self,
        card: &WearosCard,
        ease: i32,
        pool: &PoolContext,
    ) -> Result<PoolGradeOutcome, CoreError> {
        let now_ms = unix_millis() as i64;
        let now_secs = now_ms.div_euclid(1_000);
        let today = day_index(now_ms, pool.utc_offset_secs, pool.rollover_hour);
        let (stored_card, due_day, due_at_secs) =
            self.pool_placement(card, ease, today, now_ms, now_secs);

        let snapshot = pool_card_snapshot(card.id)?.unwrap_or_else(|| PoolCardSnapshot {
            card: card.clone(),
            due_day: None,
            due_at_secs: None,
            graded: false,
        });
        let kind_before = grade_kind_before(card.state.as_ref());

        pool_update_card(stored_card.clone(), due_day, due_at_secs, true)?;
        let grade_log_id = pool_log_grade(self.deck_id, card.id, today, kind_before)?;

        Ok(PoolGradeOutcome {
            stored_card,
            due_at_secs,
            now_secs,
            undo: PoolUndo { snapshot, grade_log_id },
        })
    }

    /// The card's next pool placement for this grade: the stored card with its refreshed state and
    /// labels, plus the due day (day+) or due time (sub-day) it is scheduled for.
    ///
    /// With the deck's config and the card's state this mirrors the interval previews; without them
    /// (older phone, mock cards) it falls back to the shipped label's unit, hiding the card until
    /// tomorrow when the label is a day+ interval. The next phone refresh corrects either guess.
    fn pool_placement(
        &self,
        card: &WearosCard,
        ease: i32,
        today: i64,
        now_ms: i64,
        now_secs: i64,
    ) -> (WearosCard, Option<i64>, Option<i64>) {
        let (Some(config), Some(state)) = (&self.schedule_config, &card.state) else {
            let is_step = interval_label(card, ease).map_or(ease == 1, label_is_subday);
            let (due_day, due_at_secs) =
                if is_step { (None, Some(now_secs + 600)) } else { (Some(today + 1), None) };
            return (WearosCard { due_day, due_at_secs, ..card.clone() }, due_day, due_at_secs);
        };

        let slot = usize::try_from(ease - 1).unwrap_or(0).min(3);
        let secs = preview_interval_secs(config, state, now_ms).get(slot).copied().unwrap_or(0.0);
        let next_state = CardScheduleState {
            last_review_secs: now_secs,
            ..advance_state(config, state, ease, now_ms)
        };
        let (due_day, due_at_secs) = if secs < SECS_PER_DAY {
            (None, Some(now_secs + secs.round() as i64))
        } else {
            (Some(today + next_state.interval_days.round().max(1.0) as i64), None)
        };
        let labels = preview_labels_for_buttons(config, &next_state, now_ms, card.button_count);
        let stored = WearosCard {
            next_review_times: labels,
            state: Some(next_state),
            due_day,
            due_at_secs,
            ..card.clone()
        };
        (stored, due_day, due_at_secs)
    }

    /// How many learning-step reappearances this session tolerates before forcing graduation.
    ///
    /// With the deck's step list, walking the ladder with Good graduates the card at the last step
    /// without ever reaching this bound - it is the count of steps plus one only so that a card
    /// repeating a single step (Hard) still terminates.
    fn requeue_cap(&self) -> u32 {
        self.schedule_config.as_ref().map_or(MAX_LEARN_REQUEUES, |c| c.learn_steps.len() as u32 + 1)
    }

    /// Whether the chosen button lands on a learning step, so the card should reappear this session
    /// rather than graduate out.
    ///
    /// Prefers the interval we compute from the deck's own config, which is the value Anki would
    /// schedule; falls back to reading the shipped label's unit (seconds/minutes/hours) when the
    /// phone sent no config or the card carries no state (older builds, mock cards). With neither,
    /// only Again reappears.
    fn is_learning_step(&self, card: &WearosCard, ease: i32) -> bool {
        if let (Some(config), Some(state)) = (&self.schedule_config, &card.state) {
            // The computed previews are always in ease order (Again/Hard/Good/Easy), unlike the
            // shipped label list, which drops Hard for 3-button cards.
            let slot = usize::try_from(ease - 1).unwrap_or(0).min(3);
            let secs = preview_interval_secs(config, state, unix_millis() as i64);
            return secs.get(slot).is_some_and(|secs| *secs < SECS_PER_DAY);
        }
        interval_label(card, ease).map_or(ease == 1, label_is_subday)
    }

    /// The card as it will be after this grade, with its interval previews recomputed from the
    /// deck's own scheduling config. Falls back to the card untouched when the phone shipped no
    /// config or no per-card state (older builds, mock cards), leaving AnkiDroid's labels in place.
    fn refresh_previews(&self, card: &WearosCard, ease: i32) -> WearosCard {
        let (Some(config), Some(state)) = (&self.schedule_config, &card.state) else {
            return card.clone();
        };
        // unix_millis() is u128 for the answer id; the scheduler takes i64 milliseconds.
        let now_ms = unix_millis() as i64;
        let next_state = advance_state(config, state, ease, now_ms);
        // Labels keep the card's own button shape (3-button cards have no Hard), so the watch UI can
        // render them against its buttons whether they were recomputed here or shipped by the phone.
        let labels = preview_labels_for_buttons(config, &next_state, now_ms, card.button_count);
        WearosCard { next_review_times: labels, state: Some(next_state), ..card.clone() }
    }
}

#[uniffi::export]
impl ReviewSession {
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

        // Pool mode also put the grade back into the pool row and grade log; restore both, or fail
        // loudly with the event back on history so nothing but the buffer diverges.
        if let Some(undo) = &event.pool_undo {
            let restore = pool_update_card(
                undo.snapshot.card.clone(),
                undo.snapshot.due_day,
                undo.snapshot.due_at_secs,
                undo.snapshot.graded,
            )
            .and_then(|()| pool_unlog_grade(undo.grade_log_id));
            if let Err(e) = restore {
                self.history.lock().unwrap().push(event);
                return Err(e);
            }
        }

        {
            let mut queue = self.queue.lock().unwrap();
            if event.requeued {
                if event.pool_undo.is_some() {
                    // Pool mode slots the requeued copy by due time, so it is not necessarily the
                    // tail: pull back the copy carrying this card's id.
                    let card_id = event.front_before.card.id;
                    if let Some(pos) = queue.iter().rposition(|qc| qc.card.id == card_id) {
                        queue.remove(pos);
                    }
                } else {
                    // The copy we pushed on grade is the newest tail entry.
                    queue.pop_back();
                }
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

/// The kind of card a grade consumed, for the pool's daily limits: 0 new, 2 review, else learning
/// (including relearning). A card that was new is queue 0; a review-queue card is queue 2, or a
/// review card sitting somewhere other than the learning queue.
fn grade_kind_before(state: Option<&CardScheduleState>) -> i64 {
    match state {
        Some(s) if s.queue == 0 => GRADE_KIND_NEW,
        // Interday learning (queue 3) counts against the review limit in rslib v3.
        Some(s) if s.queue == 2 || s.queue == 3 || (s.card_type == 2 && s.queue != 1) => {
            GRADE_KIND_REVIEW
        }
        _ => GRADE_KIND_LEARNING,
    }
}

/// True for sub-day interval units (seconds/minutes/hours), false for day/month/year. Labels look
/// like "45s", "1m", "2h", "4d", "3mo", "1y"; note "m" is minutes but "mo" is months.
fn label_is_subday(label: &str) -> bool {
    let unit: String = label.chars().filter(char::is_ascii_alphabetic).collect();
    matches!(unit.as_str(), "s" | "m" | "h")
}
