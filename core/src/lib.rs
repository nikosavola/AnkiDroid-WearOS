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

/// A flat deck as AnkiDroid exposes it: an id plus a `::`-separated full name. Fed to
/// [deck_children] to drive the watch's folder navigation.
#[derive(uniffi::Record, Clone)]
pub struct DeckInfo {
    pub id: i64,
    pub full_name: String,
}

/// One entry in a folder level: either a folder to descend into (`is_folder`) or a studyable deck.
/// `id` is the deck id at this exact node, or -1 when a folder has no directly-studyable deck of its
/// own. `path` is the full `::`-joined path (used as the key to descend).
#[derive(uniffi::Record, Clone)]
pub struct DeckNodeInfo {
    pub id: i64,
    pub name: String,
    pub path: String,
    pub is_folder: bool,
}

/// The contents of one folder level in the deck hierarchy.
#[derive(uniffi::Record)]
pub struct DeckLevel {
    /// The deck sitting exactly at the current path, present when the folder itself is studyable
    /// (Anki parent decks are real decks). Null at the root.
    pub own_deck: Option<DeckNodeInfo>,
    /// Immediate children, folders first then decks, each sorted by name.
    pub children: Vec<DeckNodeInfo>,
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
    let conn = Connection::open(&db_path)
        .map_err(|e| CoreError::DatabaseError { error_message: e.to_string() })?;

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
    .map_err(|e| CoreError::DatabaseError { error_message: e.to_string() })?;

    {
        let mut db = DB_CONN.lock().unwrap();
        *db = Some(db_path);
    }

    Ok(())
}

fn get_db_connection() -> Result<Connection, CoreError> {
    let db = DB_CONN.lock().unwrap();
    if let Some(path) = &*db {
        Connection::open(path)
            .map_err(|e| CoreError::DatabaseError { error_message: e.to_string() })
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

// ==========================================
// 4. Answer Buffering and Reconcile
// ==========================================

#[uniffi::export]
pub fn save_answer(answer: WearosAnswer) -> Result<(), CoreError> {
    let conn = get_db_connection()?;
    let now = SystemTime::now().duration_since(SystemTime::UNIX_EPOCH).unwrap().as_secs() as i64;

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
        .map_err(|e| CoreError::DatabaseError { error_message: e.to_string() })?;

    let mut answers = Vec::new();
    for row in rows {
        answers.push(row.map_err(|e| CoreError::DatabaseError { error_message: e.to_string() })?);
    }

    Ok(answers)
}

#[uniffi::export]
pub fn remove_buffered_answers(ids: Vec<String>) -> Result<(), CoreError> {
    let mut conn = get_db_connection()?;
    let tx = conn
        .transaction()
        .map_err(|e| CoreError::DatabaseError { error_message: e.to_string() })?;

    for id in ids {
        tx.execute("DELETE FROM buffered_answers WHERE id = ?1", params![id])
            .map_err(|e| CoreError::DatabaseError { error_message: e.to_string() })?;
    }

    tx.commit().map_err(|e| CoreError::DatabaseError { error_message: e.to_string() })?;
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

#[uniffi::export]
pub fn clean_html(raw_html: String) -> String {
    // 1. Drop <script>/<style> blocks, HTML comments (Anki templates hide fields like
    //    {{Pitch Accent}} in them) and [sound:...] media refs before anything else, so none of
    //    their raw source leaks as text (some note types embed a <script> for click behaviour).
    let without_scripts = strip_tag_blocks(&raw_html, "script");
    let without_styles = strip_tag_blocks(&without_scripts, "style");
    let without_comments = strip_delimited(&without_styles, "<!--", "-->");
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
    let without_scripts = strip_tag_blocks(&raw_html, "script");
    let without_styles = strip_tag_blocks(&without_scripts, "style");
    let without_comments = strip_delimited(&without_styles, "<!--", "-->");
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

/// Remove `<tag ...>...</tag>` blocks (inclusive, case-insensitive), e.g. `<script>`/`<style>`
/// whose raw source must never render as card text. ASCII-lowercasing preserves byte offsets (only
/// ASCII bytes change), so indices into `lower` map straight onto `s`.
fn strip_tag_blocks(s: &str, tag: &str) -> String {
    let lower = s.to_ascii_lowercase();
    let open_lc = format!("<{}", tag.to_ascii_lowercase());
    let close_lc = format!("</{}>", tag.to_ascii_lowercase());
    let mut out = String::new();
    let mut i = 0;
    while let Some(rel) = lower[i..].find(&open_lc) {
        let start = i + rel;
        out.push_str(&s[i..start]);
        if let Some(erel) = lower[start..].find(&close_lc) {
            i = start + erel + close_lc.len();
        } else {
            i = s.len(); // unterminated block: drop the rest
            break;
        }
    }
    out.push_str(&s[i..]);
    out
}

/// Remove every `open`..`close` span (inclusive) from `s`, including its contents.
fn strip_delimited(s: &str, open: &str, close: &str) -> String {
    let mut out = String::new();
    let mut rest = s;
    while let Some(start) = rest.find(open) {
        out.push_str(&rest[..start]);
        if let Some(end) = rest[start + open.len()..].find(close) {
            rest = &rest[start + open.len() + end + close.len()..];
        } else {
            rest = "";
            break;
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
// 5b. Deck hierarchy (Anki `::` folders)
// ==========================================

/// Resolve one level of the deck folder tree parsed from Anki `::`-separated names.
///
/// `path` is the current folder ("" for the root, e.g. "Japani" or "Japani::文法" deeper in). A node
/// can be both a folder and a studyable deck: entering it exposes an `own_deck` (study the folder
/// itself) alongside its `children`. Folders are children that have anything nested below them; leaf
/// decks are children with no descendants.
#[uniffi::export]
pub fn deck_children(decks: Vec<DeckInfo>, path: String) -> DeckLevel {
    use std::collections::BTreeMap;

    let prefix = if path.is_empty() { String::new() } else { format!("{path}::") };

    let own_deck = decks.iter().find(|d| d.full_name == path).map(|d| DeckNodeInfo {
        id: d.id,
        name: path.rsplit("::").next().unwrap_or(&path).to_string(),
        path: path.clone(),
        is_folder: false,
    });

    // segment name -> (own deck id at that child path if any, has descendants)
    let mut seen: BTreeMap<String, (i64, bool)> = BTreeMap::new();
    for d in &decks {
        // Only decks strictly below the current path are children; the own deck (== path) is skipped.
        let rest = if path.is_empty() {
            d.full_name.as_str()
        } else if let Some(r) = d.full_name.strip_prefix(&prefix) {
            r
        } else {
            continue;
        };
        if rest.is_empty() {
            continue;
        }
        let child = rest.split("::").next().unwrap_or(rest).to_string();
        let child_path = format!("{prefix}{child}");
        let entry = seen.entry(child).or_insert((-1, false));
        if rest.contains("::") {
            entry.1 = true; // a deeper segment exists, so this child is a folder
        }
        if d.full_name == child_path {
            entry.0 = d.id; // this child is itself a studyable deck
        }
    }

    let mut children: Vec<DeckNodeInfo> = seen
        .into_iter()
        .map(|(name, (id, is_folder))| {
            let child_path = format!("{prefix}{name}");
            DeckNodeInfo { id, name, path: child_path, is_folder }
        })
        .collect();
    // Folders first, then decks; alphabetical within each group (BTreeMap already sorted by name).
    children.sort_by(|a, b| b.is_folder.cmp(&a.is_folder));

    DeckLevel { own_deck, children }
}

/// Parse AnkiDroid's NEXT_REVIEW_TIMES: a JSON-array string whose numbers are wrapped in Unicode
/// bidi isolates (e.g. `["\u{2068}10\u{2069}m", ...]`). Returns clean values like "10m", falling
/// back to a lenient bracket split when the input isn't valid JSON.
#[uniffi::export]
pub fn parse_review_times(raw: String) -> Vec<String> {
    // Drop bidi isolate controls (LRI/RLI/FSI/PDI) Anki wraps around the numbers.
    let cleaned: String = raw
        .chars()
        .filter(|c| !matches!(c, '\u{2066}' | '\u{2067}' | '\u{2068}' | '\u{2069}'))
        .collect();
    let trimmed = cleaned.trim();

    if let Ok(serde_json::Value::Array(arr)) = serde_json::from_str::<serde_json::Value>(trimmed) {
        return arr
            .iter()
            .filter_map(|v| v.as_str().map(|s| s.trim().to_string()))
            .filter(|s| !s.is_empty())
            .collect();
    }

    trimmed
        .trim_start_matches('[')
        .trim_end_matches(']')
        .split(',')
        .map(|s| s.trim().trim_matches('"').trim().to_string())
        .filter(|s| !s.is_empty())
        .collect()
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
        let html =
            "<div style='font-size:25px'><b><ruby><rb>答</rb><rt>こた</rt></ruby>え</b></div>\
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
        let html =
            "答える\n<!-- {{#Pitch Accent}}<div>{{Pitch Accent}}</div>{{/Pitch Accent}} -->\n\
                    <div>to answer</div>\n[sound:answer_コタエ.mp3]\n<img src=\"x.webp\">"
                .to_string();
        let clean = clean_html(html);
        assert!(!clean.contains("Pitch Accent"), "comment leaked: {clean}");
        assert!(!clean.contains("sound:"), "sound tag leaked: {clean}");
        assert!(clean.contains("答える"));
        assert!(clean.contains("to answer"));
    }

    fn sample_decks() -> Vec<DeckInfo> {
        // Mirrors the user's real collection shape: 文法 is both a folder and a studyable deck.
        [
            (1, "Japani"),
            (2, "Japani::Kaishi 1.5k"),
            (3, "Japani::文法"),
            (4, "Japani::文法::5. N2"),
            (5, "Japani::文法::6. N1"),
            (6, "Fysiikka::Electricity"),
        ]
        .iter()
        .map(|(id, name)| DeckInfo { id: *id, full_name: name.to_string() })
        .collect()
    }

    #[test]
    fn test_deck_children_root() {
        let level = deck_children(sample_decks(), String::new());
        assert!(level.own_deck.is_none(), "root has no own deck");
        // Two top-level folders, both with children; sorted folders-first.
        let names: Vec<_> = level.children.iter().map(|c| c.name.as_str()).collect();
        assert_eq!(names, vec!["Fysiikka", "Japani"]);
        assert!(level.children.iter().all(|c| c.is_folder));
        // Japani has its own deck id even though it's a folder.
        let japani = level.children.iter().find(|c| c.name == "Japani").unwrap();
        assert_eq!(japani.id, 1);
        assert_eq!(japani.path, "Japani");
    }

    #[test]
    fn test_deck_children_folder_and_deck() {
        // Inside Japani: own deck present, Kaishi is a leaf, 文法 is a folder (and its own deck).
        let level = deck_children(sample_decks(), "Japani".to_string());
        assert_eq!(level.own_deck.as_ref().unwrap().id, 1);
        assert_eq!(level.own_deck.as_ref().unwrap().name, "Japani");

        let folder = level.children.iter().find(|c| c.name == "文法").unwrap();
        assert!(folder.is_folder);
        assert_eq!(folder.id, 3, "文法 folder is also a studyable deck");
        assert_eq!(folder.path, "Japani::文法");

        let leaf = level.children.iter().find(|c| c.name == "Kaishi 1.5k").unwrap();
        assert!(!leaf.is_folder);
        assert_eq!(leaf.id, 2);

        // Folders sort before leaf decks.
        assert!(level.children[0].is_folder);
    }

    #[test]
    fn test_deck_children_leaf_level() {
        let level = deck_children(sample_decks(), "Japani::文法".to_string());
        assert_eq!(level.own_deck.as_ref().unwrap().id, 3);
        let names: Vec<_> = level.children.iter().map(|c| c.name.as_str()).collect();
        assert_eq!(names, vec!["5. N2", "6. N1"]);
        assert!(level.children.iter().all(|c| !c.is_folder));
        assert_eq!(level.children[0].id, 4);
    }

    #[test]
    fn test_strips_script_and_style() {
        // A note type embeds a <script> for click behaviour and a <style> block; neither's source
        // may leak as card text (this was showing "function ClickCard() {...}" on the watch).
        let html = "想像<script>function ClickCard() { const x = document.getElementById(\"click\"); \
                    }</script><style>.card { color: red; }</style><b>answer</b>"
            .to_string();
        let display = html_for_display(html.clone());
        assert!(!display.contains("ClickCard"), "script leaked: {display}");
        assert!(!display.contains("color: red"), "style leaked: {display}");
        assert!(display.contains("<b>answer</b>"), "formatting dropped: {display}");
        assert!(display.contains("想像"));

        let clean = clean_html(html);
        assert!(!clean.contains("ClickCard"), "script leaked into clean: {clean}");
        assert!(!clean.contains("color: red"), "style leaked into clean: {clean}");
        assert!(clean.contains("想像") && clean.contains("answer"));
    }

    #[test]
    fn test_parse_review_times() {
        // Valid JSON array with bidi isolates around the numbers (real AnkiDroid shape).
        let raw = "[\"\u{2068}10\u{2069}m\", \"\u{2068}2\u{2069}d\", \"\u{2068}4\u{2069}d\"]";
        assert_eq!(parse_review_times(raw.to_string()), vec!["10m", "2d", "4d"]);

        // Lenient fallback for a non-JSON comma list.
        assert_eq!(
            parse_review_times("1m, 10m, 4d, 7d".to_string()),
            vec!["1m", "10m", "4d", "7d"]
        );

        assert!(parse_review_times(String::new()).is_empty());
    }

    #[test]
    fn test_deck_children_no_partial_segment_match() {
        // "Japan" must not match "Japani" as a child prefix.
        let decks = vec![
            DeckInfo { id: 1, full_name: "Japani::x".to_string() },
            DeckInfo { id: 2, full_name: "Japanese".to_string() },
        ];
        let level = deck_children(decks, "Japani".to_string());
        let names: Vec<_> = level.children.iter().map(|c| c.name.as_str()).collect();
        assert_eq!(names, vec!["x"]);
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
        let batch =
            WearosDueBatch { deck_id: 123, deck_name: "Test Deck".to_string(), cards: vec![card] };

        let bytes = serialize_due_batch(batch).unwrap();
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
        let db_path = temp_dir.join("test_wearos_buffer.db").to_string_lossy().to_string();

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

        save_answer(ans1).unwrap();
        save_answer(ans2).unwrap();

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
