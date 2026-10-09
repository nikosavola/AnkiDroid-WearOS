//! Anki's `{{type:Field}}` templates render as a `[[type:Field]]` placeholder, which the reviewer
//! fills in. Ported from AnkiDroid's `TypeAnswer` and rslib's `compare_answer`: the phone fills the
//! placeholder when nothing was typed, and the watch compares what the user typed against that.

use std::fmt::Write as _;

use difflib::sequencematcher::SequenceMatcher;
use unicode_normalization::UnicodeNormalization;

use crate::html::decode_entities;

const OPEN: &str = "[[type:";
const CLOSE: &str = "]]";
const TYPEANS_OPEN: &str = "<code id=typeans>";
const TYPEANS_CLOSE: &str = "</code>";

/// The field a `[[type:...]]` placeholder compares against, from its first occurrence.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct TypeAnswerField {
    pub field: String,
    /// `cloze:` narrows the field to the card's own cloze deletions.
    pub cloze: bool,
}

/// The type-answer field named in [`html`], or `None` when the side has no placeholder.
#[uniffi::export]
pub fn type_answer_field(html: String) -> Option<TypeAnswerField> {
    let start = html.find(OPEN)? + OPEN.len();
    let end = start + html[start..].find(CLOSE)?;
    let mut parts: Vec<&str> = html[start..end].split(':').collect();
    // Field names can't contain ':', so everything before the last part is a modifier
    // (cloze, nc, nosuggest).
    let field = parts.pop()?.to_string();
    if field.is_empty() {
        return None;
    }
    Some(TypeAnswerField { field, cloze: parts.contains(&"cloze") })
}

/// The answer for cloze `idx` in a cloze field: hints cut, one copy when every deletion matches,
/// otherwise all of them comma-separated (AnkiDroid's `contentForCloze`, matching desktop).
#[uniffi::export]
pub fn content_for_cloze(text: String, idx: u32) -> String {
    let open = format!("{{{{c{idx}::");
    let mut matches: Vec<&str> = Vec::new();
    let mut rest = text.as_str();
    while let Some(at) = rest.find(&open) {
        let body = &rest[at + open.len()..];
        // AnkiDroid's `(.+?)\}\}` needs at least one character and doesn't cross a line break.
        let first_len = body.chars().next().map_or(0, char::len_utf8);
        let Some(end) =
            body[first_len..].find("}}").map(|e| e + first_len).filter(|_| first_len > 0)
        else {
            break;
        };
        let inner = &body[..end];
        if inner.contains('\n') {
            rest = &rest[at + open.len()..];
            continue;
        }
        matches.push(inner.split_once("::").map_or(inner, |(answer, _hint)| answer));
        rest = &body[end + CLOSE.len()..];
    }
    let first = matches.first().copied().unwrap_or_default();
    if matches.iter().all(|m| *m == first) { first.to_string() } else { matches.join(", ") }
}

/// Replace every `[[type:...]]` in [`html`] with the expected answer, or remove it.
///
/// [`expected`] goes in as plain text inside `<code id=typeans>`, rslib's markup when nothing was
/// typed. `None` (the question side) or an empty answer removes the placeholder.
#[uniffi::export]
pub fn fill_type_answer(html: String, expected: Option<String>) -> String {
    let replacement = expected
        .map(|e| strip_expected(&e))
        .filter(|e| !e.is_empty())
        .map_or_else(String::new, |e| format!("<code id=typeans>{}</code>", escape_minimal(&e)));
    let mut out = String::new();
    let mut rest = html.as_str();
    while let Some(start) = rest.find(OPEN) {
        let Some(len) = rest[start + OPEN.len()..].find(CLOSE) else {
            break;
        };
        out.push_str(&rest[..start]);
        out.push_str(&replacement);
        rest = &rest[start + OPEN.len() + len + CLOSE.len()..];
    }
    out.push_str(rest);
    out
}

/// rslib's `strip_expected`: media refs and markup gone, line breaks as single spaces.
fn strip_expected(expected: &str) -> String {
    crate::html::clean_html(expected.to_string())
        .split('\n')
        .map(str::trim)
        .filter(|line| !line.is_empty())
        .collect::<Vec<_>>()
        .join(" ")
}

/// The expected answer from a filled back's `<code id=typeans>` element, as plain text, or `None`
/// when the side has no type-in answer (and so nothing for the user to type).
#[uniffi::export]
pub fn type_answer_expected(html: String) -> Option<String> {
    let (start, end) = typeans_range(&html)?;
    Some(decode_entities(&html[start..end]))
}

/// The filled [answer_html] with its `<code id=typeans>` element replaced by the typed diff.
///
/// `None` when there is no type-in answer or nothing was typed, leaving the plain expected answer
/// AnkiDroid shows. The diff is rslib's `compare_answer` (default case-insensitive, combining; the
/// rare `nc` variant is not ported). rslib marks runs with the `typeGood`/`typeBad`/`typeMissed
/// classes Anki colours as backgrounds; the watch's HTML import carries none of that, so the runs
/// are emitted as foreground colours instead.
#[uniffi::export]
pub fn compare_typed_answer(answer_html: String, typed: String) -> Option<String> {
    if typed.is_empty() {
        return None;
    }
    let (open, inner_end) = typeans_range(&answer_html)?;
    let expected = decode_entities(&answer_html[open..inner_end]);
    let diff = diff_html(&expected, &typed);
    let after = &answer_html[inner_end + TYPEANS_CLOSE.len()..];
    Some(format!("{}{}{}", &answer_html[..open - TYPEANS_OPEN.len()], diff, after))
}

/// The `(content_start, content_end)` range of the typeans element, or `None` without one.
fn typeans_range(html: &str) -> Option<(usize, usize)> {
    let start = html.find(TYPEANS_OPEN)? + TYPEANS_OPEN.len();
    let end = start + html[start..].find(TYPEANS_CLOSE)?;
    Some((start, end))
}

/// rslib's `compare_answer` once the empty-typed case is handled, with the watch's colour markup.
fn diff_html(expected: &str, typed: &str) -> String {
    let typed: Vec<char> = typed.nfc().collect();
    let expected: Vec<char> = expected.nfc().collect();
    let folded_typed = fold_case(&typed);
    let folded_expected = fold_case(&expected);
    if folded_typed == folded_expected {
        return format!(
            "{TYPEANS_OPEN}<span>{}</span>{TYPEANS_CLOSE}",
            escape_minimal(&expected.iter().collect::<String>())
        );
    }
    let mut matcher = SequenceMatcher::new(&folded_typed, &folded_expected);
    let mut typed_tokens: Vec<DiffToken> = Vec::new();
    let mut expected_tokens: Vec<DiffToken> = Vec::new();
    for opcode in matcher.get_opcodes() {
        let typed_slice = slice(&typed, opcode.first_start, opcode.first_end);
        let expected_slice = slice(&expected, opcode.second_start, opcode.second_end);
        match opcode.tag.as_str() {
            "equal" => {
                typed_tokens.push(DiffToken::good(typed_slice));
                expected_tokens.push(DiffToken::good(expected_slice));
            }
            "delete" => typed_tokens.push(DiffToken::bad(typed_slice)),
            "insert" => {
                typed_tokens.push(DiffToken::missing("-".repeat(expected_slice.chars().count())));
                expected_tokens.push(DiffToken::missing(expected_slice));
            }
            "replace" => {
                typed_tokens.push(DiffToken::bad(typed_slice));
                expected_tokens.push(DiffToken::missing(expected_slice));
            }
            _ => {}
        }
    }
    let mut out = format!(
        "{TYPEANS_OPEN}{}<br><span id=typearrow>&darr;</span><br>",
        render_tokens(&typed_tokens)
    );
    let _ = write!(out, "{}{TYPEANS_CLOSE}", render_tokens(&expected_tokens));
    out
}

/// rslib's case folding: each char lowercased on its own, so the folded strings stay aligned with
/// the originals; word-final sigma and long s map to the letter their uppercase folds to.
fn fold_case(chars: &[char]) -> Vec<char> {
    chars
        .iter()
        .map(|&c| match c {
            'ς' => 'σ',
            'ſ' => 's',
            _ => c.to_lowercase().next().unwrap_or(c),
        })
        .collect()
}

fn slice(chars: &[char], start: usize, end: usize) -> String {
    chars[start..end].iter().collect()
}

/// The good runs keep the ambient colour; wrong typed chars read red and expected-side misses grey,
/// so both diff lines read on the watch's dark theme. AnkiDroid colours these as backgrounds
/// (.typeBad/.typeMissed), which no HTML importer the watch has can carry.
fn render_tokens(tokens: &[DiffToken]) -> String {
    let mut out = String::new();
    for token in tokens {
        let encoded = escape_minimal(&isolate_leading_mark(&token.text));
        match token.kind {
            DiffTokenKind::Good => {
                let _ = write!(out, "<span>{encoded}</span>");
            }
            DiffTokenKind::Bad => {
                let _ = write!(out, "<font color=\"#ff5252\">{encoded}</font>");
            }
            DiffTokenKind::Missing => {
                let _ = write!(out, "<font color=\"#9e9e9e\">{encoded}</font>");
            }
        }
    }
    out
}

/// Prefix a leading combining mark with a non-breaking space so it can't join the previous token.
fn isolate_leading_mark(text: &str) -> String {
    if text.chars().next().is_some_and(unicode_normalization::char::is_combining_mark) {
        format!("\u{a0}{text}")
    } else {
        text.to_string()
    }
}

enum DiffTokenKind {
    Good,
    Bad,
    Missing,
}

struct DiffToken {
    kind: DiffTokenKind,
    text: String,
}

impl DiffToken {
    fn new(kind: DiffTokenKind, text: String) -> Self {
        Self { kind, text }
    }

    fn good(text: String) -> Self {
        Self::new(DiffTokenKind::Good, text)
    }

    fn bad(text: String) -> Self {
        Self::new(DiffTokenKind::Bad, text)
    }

    fn missing(text: String) -> Self {
        Self::new(DiffTokenKind::Missing, text)
    }
}

fn escape_minimal(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    for c in s.chars() {
        match c {
            '&' => out.push_str("&amp;"),
            '<' => out.push_str("&lt;"),
            '>' => out.push_str("&gt;"),
            '"' => out.push_str("&quot;"),
            '\'' => out.push_str("&#x27;"),
            _ => out.push(c),
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn finds_the_field_and_its_modifiers() {
        let q = "Front<br>[[type:Back]]".to_string();
        assert_eq!(
            type_answer_field(q),
            Some(TypeAnswerField { field: "Back".into(), cloze: false })
        );
        let cloze = "x [[type:nc:cloze:Text]]".to_string();
        assert_eq!(
            type_answer_field(cloze),
            Some(TypeAnswerField { field: "Text".into(), cloze: true })
        );
        assert_eq!(type_answer_field("no placeholder".into()), None);
        assert_eq!(type_answer_field("[[type:]]".into()), None);
    }

    #[test]
    fn cloze_content_matches_ankidroid() {
        let text = "{{c1::Hello}} {{c2::World::hint}} {{c1::Hello}}".to_string();
        assert_eq!(content_for_cloze(text.clone(), 1), "Hello");
        assert_eq!(content_for_cloze(text, 2), "World");
        let distinct = "{{c1::a}} and {{c1::b::why}}".to_string();
        assert_eq!(content_for_cloze(distinct, 1), "a, b");
        assert_eq!(content_for_cloze("{{c1::a}}".into(), 3), "");
        // Multi-byte text either side of the deletion.
        assert_eq!(content_for_cloze("日本{{c1::語}}です".into(), 1), "語");
    }

    #[test]
    fn question_side_drops_the_placeholder() {
        let q = "<div>Capital of France?</div>\n[[type:Back]]".to_string();
        assert_eq!(fill_type_answer(q, None), "<div>Capital of France?</div>\n");
    }

    #[test]
    fn answer_side_shows_the_expected_answer_as_rslib_does() {
        let a = "Q<hr id=answer>[[type:Back]]".to_string();
        let expected = Some("<b>Paris</b> &amp; <br>Versailles [sound:p.mp3]".to_string());
        assert_eq!(
            fill_type_answer(a, expected),
            "Q<hr id=answer><code id=typeans>Paris &amp; Versailles</code>"
        );
        // An empty field shows nothing rather than an empty box.
        assert_eq!(fill_type_answer("[[type:Back]]".into(), Some(" ".into())), "");
        // Unterminated placeholders are left alone.
        assert_eq!(fill_type_answer("[[type:Back".into(), None), "[[type:Back");
    }

    #[test]
    fn expected_answer_reads_back_out_of_the_filled_back() {
        let back = "Q<hr id=answer><code id=typeans>Paris &amp; L&apos;yon</code>".to_string();
        assert_eq!(type_answer_expected(back), Some("Paris & L'yon".to_string()));
        assert_eq!(type_answer_expected("Q<hr id=answer>plain".into()), None);
        assert_eq!(
            type_answer_expected("Q<hr id=answer><code id=typeans></code>".into()),
            Some(String::new())
        );
    }

    #[test]
    fn typed_diff_matches_rslib() {
        let back = "Q<hr id=answer><code id=typeans>123</code>".to_string();
        // Nothing typed: no diff, the plain expected answer stays.
        assert_eq!(compare_typed_answer(back.clone(), String::new()), None);
        // Correct (case folded): one good run, the expected text as written.
        let paris = "Q<hr id=answer><code id=typeans>Paris</code>".to_string();
        assert_eq!(
            compare_typed_answer(paris, "paris".into()),
            Some("Q<hr id=answer><code id=typeans><span>Paris</span></code>".to_string())
        );
        // rslib's incorrect_input_is_not_collapsed shape.
        assert_eq!(
            compare_typed_answer(back, "1123".into()),
            Some(
                "Q<hr id=answer><code id=typeans><font color=\"#ff5252\">1</font><span>123</span>\
                    <br><span id=typearrow>&darr;</span><br><span>123</span></code>"
                    .to_string()
            )
        );
    }

    #[test]
    fn typed_diff_folds_case_and_marks_missed_chars() {
        // rslib's Straße case: the expected line keeps its own casing, misses grey.
        let back = "Q<hr id=answer><code id=typeans>Straße</code>".to_string();
        assert_eq!(
            compare_typed_answer(back, "STRASSE".into()),
            Some(
                "Q<hr id=answer><code id=typeans><span>STRA</span><font color=\"#ff5252\">SS</font><span>E</span>\
                    <br><span id=typearrow>&darr;</span><br><span>Stra</span><font color=\"#9e9e9e\">ß</font><span>e</span></code>"
                    .to_string()
            )
        );
        // Missed chars show as dashes on the typed line (rslib's нос case).
        let back = "Q<hr id=answer><code id=typeans>нос</code>".to_string();
        assert_eq!(
            compare_typed_answer(back, "нс".into()),
            Some(
                "Q<hr id=answer><code id=typeans><span>н</span><font color=\"#9e9e9e\">-</font><span>с</span>\
                    <br><span id=typearrow>&darr;</span><br><span>н</span><font color=\"#9e9e9e\">о</font><span>с</span></code>"
                    .to_string()
            )
        );
        // Escaped output for typed markup.
        let back = "Q<hr id=answer><code id=typeans>a &lt; b</code>".to_string();
        assert_eq!(
            compare_typed_answer(back, "a < b".into()),
            Some("Q<hr id=answer><code id=typeans><span>a &lt; b</span></code>".to_string())
        );
    }
}
