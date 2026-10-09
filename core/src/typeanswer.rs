//! Anki's `{{type:Field}}` templates render as a `[[type:Field]]` placeholder on both sides, which
//! the reviewer fills in. Ported from AnkiDroid's `TypeAnswer` and rslib's `compare_answer`, for the
//! case where nothing was typed: the question side drops the placeholder and the answer side shows
//! the expected answer.

const OPEN: &str = "[[type:";
const CLOSE: &str = "]]";

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
}
