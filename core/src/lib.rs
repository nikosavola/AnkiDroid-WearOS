use rusqlite::{Connection, params};
use serde::{Deserialize, Serialize};
use std::sync::Mutex;
use std::time::SystemTime;

uniffi::setup_scaffolding!();

// ==========================================
// 1. Data Models
// ==========================================

#[derive(uniffi::Record, Serialize, Deserialize, Clone)]
pub struct WearosCard {
    pub id: i64,
    pub note_id: i64,
    pub ord: i32,
    pub front: String,
    pub back: String,
    pub button_count: i32,
    pub next_review_times: Vec<String>,
}

#[derive(uniffi::Record, Serialize, Deserialize, Clone)]
pub struct WearosDueBatch {
    pub deck_id: i64,
    pub deck_name: String,
    pub cards: Vec<WearosCard>,
}

#[derive(uniffi::Record, Serialize, Deserialize, Clone)]
pub struct WearosAnswer {
    pub id: String,
    pub note_id: i64,
    pub ord: i32,
    pub ease: i32,
    pub time_taken_ms: i64,
}

// ==========================================
// 2. Database & State Management
// ==========================================

static DB_CONN: Mutex<Option<String>> = Mutex::new(None);

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum CoreError {
    #[error("Database error: {error_message}")]
    DatabaseError { error_message: String },
    #[error("Serialization error: {error_message}")]
    SerializationError { error_message: String },
    #[error("State error: {error_message}")]
    StateError { error_message: String },
}

#[uniffi::export]
pub fn init_database(db_path: String) -> Result<(), CoreError> {
    let conn = Connection::open(&db_path).map_err(|e| CoreError::DatabaseError {
        error_message: e.to_string(),
    })?;

    conn.execute(
        "CREATE TABLE IF NOT EXISTS buffered_answers (
            id TEXT PRIMARY KEY,
            note_id INTEGER NOT NULL,
            card_ord INTEGER NOT NULL,
            ease INTEGER NOT NULL,
            time_taken_ms INTEGER NOT NULL,
            created_at INTEGER NOT NULL
        )",
        [],
    )
    .map_err(|e| CoreError::DatabaseError {
        error_message: e.to_string(),
    })?;

    let mut db = DB_CONN.lock().unwrap();
    *db = Some(db_path);

    Ok(())
}

fn get_db_connection() -> Result<Connection, CoreError> {
    let db = DB_CONN.lock().unwrap();
    if let Some(path) = &*db {
        Connection::open(path).map_err(|e| CoreError::DatabaseError {
            error_message: e.to_string(),
        })
    } else {
        Err(CoreError::DatabaseError {
            error_message: "Database has not been initialized. Call init_database first."
                .to_string(),
        })
    }
}

// ==========================================
// 3. Serialization Protocol (Phone <-> Watch)
// ==========================================

#[uniffi::export]
pub fn serialize_due_batch(batch: WearosDueBatch) -> Result<Vec<u8>, CoreError> {
    serde_json::to_vec(&batch).map_err(|e| CoreError::SerializationError {
        error_message: e.to_string(),
    })
}

#[uniffi::export]
pub fn deserialize_due_batch(bytes: Vec<u8>) -> Result<WearosDueBatch, CoreError> {
    serde_json::from_slice(&bytes).map_err(|e| CoreError::SerializationError {
        error_message: e.to_string(),
    })
}

#[uniffi::export]
pub fn serialize_answers(answers: Vec<WearosAnswer>) -> Result<Vec<u8>, CoreError> {
    serde_json::to_vec(&answers).map_err(|e| CoreError::SerializationError {
        error_message: e.to_string(),
    })
}

#[uniffi::export]
pub fn deserialize_answers(bytes: Vec<u8>) -> Result<Vec<WearosAnswer>, CoreError> {
    serde_json::from_slice(&bytes).map_err(|e| CoreError::SerializationError {
        error_message: e.to_string(),
    })
}

// ==========================================
// 4. Answer Buffering and Reconcile
// ==========================================

#[uniffi::export]
pub fn save_answer(answer: WearosAnswer) -> Result<(), CoreError> {
    let conn = get_db_connection()?;
    let now = SystemTime::now()
        .duration_since(SystemTime::UNIX_EPOCH)
        .unwrap()
        .as_secs() as i64;

    conn.execute(
        "INSERT OR REPLACE INTO buffered_answers (id, note_id, card_ord, ease, time_taken_ms, created_at)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
        params![
            answer.id,
            answer.note_id,
            answer.ord,
            answer.ease,
            answer.time_taken_ms,
            now
        ],
    )
    .map_err(|e| CoreError::DatabaseError { error_message: e.to_string() })?;

    Ok(())
}

#[uniffi::export]
pub fn get_buffered_answers() -> Result<Vec<WearosAnswer>, CoreError> {
    let conn = get_db_connection()?;
    let mut stmt = conn
        .prepare("SELECT id, note_id, card_ord, ease, time_taken_ms FROM buffered_answers ORDER BY created_at ASC")
        .map_err(|e| CoreError::DatabaseError { error_message: e.to_string() })?;

    let rows = stmt
        .query_map([], |row| {
            Ok(WearosAnswer {
                id: row.get(0)?,
                note_id: row.get(1)?,
                ord: row.get(2)?,
                ease: row.get(3)?,
                time_taken_ms: row.get(4)?,
            })
        })
        .map_err(|e| CoreError::DatabaseError {
            error_message: e.to_string(),
        })?;

    let mut answers = Vec::new();
    for row in rows {
        answers.push(row.map_err(|e| CoreError::DatabaseError {
            error_message: e.to_string(),
        })?);
    }

    Ok(answers)
}

#[uniffi::export]
pub fn remove_buffered_answers(ids: Vec<String>) -> Result<(), CoreError> {
    let mut conn = get_db_connection()?;
    let tx = conn.transaction().map_err(|e| CoreError::DatabaseError {
        error_message: e.to_string(),
    })?;

    for id in ids {
        tx.execute("DELETE FROM buffered_answers WHERE id = ?1", params![id])
            .map_err(|e| CoreError::DatabaseError {
                error_message: e.to_string(),
            })?;
    }

    tx.commit().map_err(|e| CoreError::DatabaseError {
        error_message: e.to_string(),
    })?;
    Ok(())
}

// ==========================================
// 5. Review Session Object
// ==========================================

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
        Self {
            batch,
            current_index: Mutex::new(0),
            is_revealed: Mutex::new(false),
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
        if index < self.batch.cards.len() {
            Some(self.batch.cards[index].clone())
        } else {
            None
        }
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
        let mut index = self.current_index.lock().unwrap();
        let mut revealed = self.is_revealed.lock().unwrap();

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
            SystemTime::now()
                .duration_since(SystemTime::UNIX_EPOCH)
                .unwrap()
                .as_millis()
        );

        let answer = WearosAnswer {
            id,
            note_id: card.note_id,
            ord: card.ord,
            ease,
            time_taken_ms,
        };

        // Attempt to buffer locally if database is configured
        if get_db_connection().is_ok() {
            let _ = save_answer(answer.clone());
        }

        *index += 1;
        *revealed = false;

        Ok(Some(answer))
    }

    pub fn get_progress_string(&self) -> String {
        let index = *self.current_index.lock().unwrap();
        format!("{}/{}", index, self.batch.cards.len())
    }
}

#[uniffi::export]
pub fn clean_html(raw_html: String) -> String {
    // 1. Drop HTML comments (Anki templates hide fields like {{Pitch Accent}} in them) and
    //    [sound:...] media refs before anything else, so their contents never leak as text.
    let without_comments = strip_delimited(&raw_html, "<!--", "-->");
    let without_sound = strip_delimited(&without_comments, "[sound:", "]");

    // 2. Convert structural HTML blocks to newlines.
    let result = without_sound
        .replace("<br>", "\n")
        .replace("<br/>", "\n")
        .replace("<br />", "\n")
        .replace("<BR>", "\n")
        .replace("<p>", "")
        .replace("</p>", "\n");

    // 3. Strip tags, keeping text. Render furigana <ruby>base<rt>reading</rt></ruby> as
    //    "base(reading)" and discard <rp> fallback parentheses.
    let mut clean = String::new();
    let mut chars = result.chars().peekable();
    let mut in_rp = false;
    while let Some(c) = chars.next() {
        if c == '<' {
            let mut tag = String::new();
            while let Some(&nc) = chars.peek() {
                chars.next();
                if nc == '>' {
                    break;
                }
                tag.push(nc);
            }
            let closing = tag.starts_with('/');
            let name = tag
                .trim_start_matches('/')
                .split(|ch: char| ch.is_whitespace())
                .next()
                .unwrap_or("")
                .to_ascii_lowercase();
            match name.as_str() {
                "rt" => clean.push(if closing { ')' } else { '(' }),
                "rp" => in_rp = !closing,
                _ => {}
            }
        } else if !in_rp {
            clean.push(c);
        }
    }

    // 4. Convert HTML entities after all tags have been removed.
    let decoded = clean
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">");

    collapse_blank_lines(&decoded).trim().to_string()
}

/// Sanitize card HTML for on-device rendering (e.g. HtmlCompat), keeping formatting tags like
/// <b>/<div>/<font> but removing what a watch can't render: HTML comments (which hide fields like
/// {{Pitch Accent}}), [sound:...] refs, and <img> (media isn't shipped to the watch). Furigana
/// <ruby>base<rt>reading</rt></ruby> becomes base(reading).
#[uniffi::export]
pub fn html_for_display(raw_html: String) -> String {
    let without_comments = strip_delimited(&raw_html, "<!--", "-->");
    let without_sound = strip_delimited(&without_comments, "[sound:", "]");
    normalize_ruby(&without_sound).trim().to_string()
}

/// Drop ruby wrapper tags and <img>, render <rt> as parenthesized readings, and discard <rp>
/// fallbacks, while passing every other tag through unchanged.
fn normalize_ruby(s: &str) -> String {
    let mut out = String::new();
    let mut chars = s.chars().peekable();
    let mut in_rp = false;
    while let Some(c) = chars.next() {
        if c == '<' {
            let mut tag = String::new();
            while let Some(&nc) = chars.peek() {
                chars.next();
                if nc == '>' {
                    break;
                }
                tag.push(nc);
            }
            let closing = tag.starts_with('/');
            let name = tag
                .trim_start_matches('/')
                .split(|ch: char| ch.is_whitespace())
                .next()
                .unwrap_or("")
                .to_ascii_lowercase();
            match name.as_str() {
                "rt" => out.push_str(if closing { ")" } else { "(" }),
                "rp" => in_rp = !closing,
                "ruby" | "rb" | "img" => {}
                _ => {
                    if !in_rp {
                        out.push('<');
                        out.push_str(&tag);
                        out.push('>');
                    }
                }
            }
        } else if !in_rp {
            out.push(c);
        }
    }
    out
}

/// Remove every `open`..`close` span (inclusive) from `s`, including its contents.
fn strip_delimited(s: &str, open: &str, close: &str) -> String {
    let mut out = String::new();
    let mut rest = s;
    while let Some(start) = rest.find(open) {
        out.push_str(&rest[..start]);
        match rest[start + open.len()..].find(close) {
            Some(end) => rest = &rest[start + open.len() + end + close.len()..],
            None => {
                rest = "";
                break;
            }
        }
    }
    out.push_str(rest);
    out
}

/// Collapse runs of blank lines (left behind by stripped comments/media) into a single blank.
fn collapse_blank_lines(s: &str) -> String {
    let mut result = String::new();
    let mut blanks = 0;
    for line in s.lines() {
        if line.trim().is_empty() {
            blanks += 1;
            if blanks == 1 {
                result.push('\n');
            }
        } else {
            blanks = 0;
            result.push_str(line.trim_end());
            result.push('\n');
        }
    }
    result
}

// ==========================================
// 6. Unit Tests
// ==========================================

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_clean_html() {
        let html =
            "<p>Hello <b>World</b>!</p><br/>Welcome &nbsp; back &amp; enjoy &lt;3.".to_string();
        let clean = clean_html(html);
        assert_eq!(clean, "Hello World!\n\nWelcome   back & enjoy <3.");
    }

    #[test]
    fn test_clean_html_ruby() {
        // Furigana becomes base(reading); <rb> is unwrapped and <rp> fallbacks are dropped.
        let html = "<ruby><rb>答</rb><rt>こた</rt></ruby>える".to_string();
        assert_eq!(clean_html(html), "答(こた)える");

        let with_rp = "<ruby>漢<rp>(</rp><rt>かん</rt><rp>)</rp></ruby>".to_string();
        assert_eq!(clean_html(with_rp), "漢(かん)");
    }

    #[test]
    fn test_html_for_display_keeps_formatting() {
        let html = "<div style='font-size:25px'><b><ruby><rb>答</rb><rt>こた</rt></ruby>え</b></div>\
                    <!-- {{Pitch Accent}} -->[sound:x.mp3]<img src=\"a.webp\">"
            .to_string();
        let out = html_for_display(html);
        assert!(out.contains("<b>"), "formatting dropped: {out}");
        assert!(out.contains("答(こた)え"), "ruby not normalized: {out}");
        assert!(!out.contains("Pitch Accent"));
        assert!(!out.contains("sound:"));
        assert!(!out.contains("<img"));
    }

    #[test]
    fn test_clean_html_strips_comments_and_sound() {
        // HTML comments (even when they contain tags/fields) and [sound:] refs must not leak.
        let html = "答える\n<!-- {{#Pitch Accent}}<div>{{Pitch Accent}}</div>{{/Pitch Accent}} -->\n\
                    <div>to answer</div>\n[sound:answer_コタエ.mp3]\n<img src=\"x.webp\">"
            .to_string();
        let clean = clean_html(html);
        assert!(!clean.contains("Pitch Accent"), "comment leaked: {clean}");
        assert!(!clean.contains("sound:"), "sound tag leaked: {clean}");
        assert!(clean.contains("答える"));
        assert!(clean.contains("to answer"));
    }

    #[test]
    fn test_serialization_due_batch() {
        let card = WearosCard {
            id: 1,
            note_id: 2,
            ord: 0,
            front: "Q".to_string(),
            back: "A".to_string(),
            button_count: 4,
            next_review_times: vec![
                "1m".to_string(),
                "10m".to_string(),
                "4d".to_string(),
                "7d".to_string(),
            ],
        };
        let batch = WearosDueBatch {
            deck_id: 123,
            deck_name: "Test Deck".to_string(),
            cards: vec![card],
        };

        let bytes = serialize_due_batch(batch.clone()).unwrap();
        let deserialized = deserialize_due_batch(bytes).unwrap();

        assert_eq!(deserialized.deck_id, 123);
        assert_eq!(deserialized.deck_name, "Test Deck");
        assert_eq!(deserialized.cards.len(), 1);
        assert_eq!(deserialized.cards[0].id, 1);
        assert_eq!(deserialized.cards[0].front, "Q");
    }

    #[test]
    fn test_review_session_state_machine() {
        let card1 = WearosCard {
            id: 1,
            note_id: 10,
            ord: 0,
            front: "Q1".to_string(),
            back: "A1".to_string(),
            button_count: 4,
            next_review_times: vec![
                "1m".to_string(),
                "10m".to_string(),
                "4d".to_string(),
                "7d".to_string(),
            ],
        };
        let card2 = WearosCard {
            id: 2,
            note_id: 20,
            ord: 1,
            front: "Q2".to_string(),
            back: "A2".to_string(),
            button_count: 3,
            next_review_times: vec!["1m".to_string(), "10m".to_string(), "4d".to_string()],
        };
        let batch = WearosDueBatch {
            deck_id: 123,
            deck_name: "Test".to_string(),
            cards: vec![card1, card2],
        };

        let session = ReviewSession::new(batch);
        assert_eq!(session.get_total_cards(), 2);
        assert_eq!(session.get_current_index(), 0);
        assert!(!session.is_revealed());

        // Check first card
        let c1 = session.get_current_card().unwrap();
        assert_eq!(c1.front, "Q1");

        session.reveal_answer();
        assert!(session.is_revealed());

        // Answer card
        let ans1 = session.answer_card(3, 5000).unwrap().unwrap();
        assert_eq!(ans1.ord, 0);
        assert_eq!(ans1.ease, 3);
        assert_eq!(ans1.time_taken_ms, 5000);

        // Check index advanced
        assert_eq!(session.get_current_index(), 1);
        assert!(!session.is_revealed());

        // Check second card
        let c2 = session.get_current_card().unwrap();
        assert_eq!(c2.front, "Q2");

        let _ = session.answer_card(4, 2000).unwrap();
        assert_eq!(session.get_current_index(), 2);
        assert!(session.get_current_card().is_none());
    }

    #[test]
    fn test_local_sqlite_buffering() {
        let temp_dir = std::env::temp_dir();
        let db_path = temp_dir
            .join("test_wearos_buffer.db")
            .to_string_lossy()
            .to_string();

        // Clean start
        let _ = std::fs::remove_file(&db_path);

        init_database(db_path.clone()).unwrap();

        let ans1 = WearosAnswer {
            id: "id_1".to_string(),
            note_id: 101,
            ord: 0,
            ease: 3,
            time_taken_ms: 4500,
        };
        let ans2 = WearosAnswer {
            id: "id_2".to_string(),
            note_id: 102,
            ord: 1,
            ease: 1,
            time_taken_ms: 12000,
        };

        save_answer(ans1.clone()).unwrap();
        save_answer(ans2.clone()).unwrap();

        let buffered = get_buffered_answers().unwrap();
        assert_eq!(buffered.len(), 2);
        assert_eq!(buffered[0].id, "id_1");
        assert_eq!(buffered[1].id, "id_2");

        remove_buffered_answers(vec!["id_1".to_string()]).unwrap();
        let buffered_after = get_buffered_answers().unwrap();
        assert_eq!(buffered_after.len(), 1);
        assert_eq!(buffered_after[0].id, "id_2");

        // Clean up
        let _ = std::fs::remove_file(&db_path);
    }
}
