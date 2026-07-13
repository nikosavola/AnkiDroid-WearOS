//! Card HTML sanitizing for on-device rendering and TTS. The watch has no WebView, so these turn
//! AnkiDroid's compiled card HTML into either plain text (TTS) or a HtmlCompat-safe subset (display).

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
                // Block-level elements Anki uses to lay out lines (it wraps most content in <div>s):
                // break the line when they close so text/TTS doesn't run together.
                "div" | "li" | "tr" | "blockquote" | "h1" | "h2" | "h3" | "h4" | "h5" | "h6"
                    if closing =>
                {
                    clean.push('\n');
                }
                _ => {}
            }
        } else if !in_rp {
            clean.push(c);
        }
    }

    // 4. Decode HTML entities (named + numeric) now that all tags are gone, so TTS doesn't read
    //    "&#39;" aloud and apostrophes/dashes come through.
    let decoded = decode_entities(&clean);

    collapse_blank_lines(&decoded).trim().to_string()
}

/// Decode HTML character references in `s`: named (`&amp;`, `&quot;`, `&apos;`, `&mdash;`, …) and
/// numeric (`&#39;`, `&#x2F;`). Single-pass — an already-escaped `&amp;lt;` decodes to `&lt;`, not
/// `<`. Unknown or unterminated references are left verbatim.
fn decode_entities(s: &str) -> String {
    let mut out = String::new();
    let mut chars = s.chars().peekable();
    while let Some(c) = chars.next() {
        if c != '&' {
            out.push(c);
            continue;
        }
        // Collect the reference body up to ';' (bounded, so a lone '&' can't scan the whole string).
        let mut ent = String::new();
        let mut terminated = false;
        for _ in 0..12 {
            match chars.peek() {
                Some(&';') => {
                    chars.next();
                    terminated = true;
                    break;
                }
                Some(&nc) if nc != '&' && !nc.is_whitespace() => {
                    ent.push(nc);
                    chars.next();
                }
                _ => break,
            }
        }
        if let Some(decoded) = decode_entity(&ent).filter(|_| terminated) {
            out.push_str(&decoded);
        } else {
            // Not a reference we recognize: emit it back untouched (with the ';' if present).
            out.push('&');
            out.push_str(&ent);
            if terminated {
                out.push(';');
            }
        }
    }
    out
}

/// Resolve a single entity body (the text between `&` and `;`) to its string, or `None` if unknown.
fn decode_entity(ent: &str) -> Option<String> {
    if let Some(rest) = ent.strip_prefix('#') {
        let code = rest
            .strip_prefix(['x', 'X'])
            .map_or_else(|| rest.parse::<u32>().ok(), |hex| u32::from_str_radix(hex, 16).ok())?;
        return char::from_u32(code).map(|c| c.to_string());
    }
    let mapped = match ent {
        "nbsp" => " ",
        "amp" => "&",
        "lt" => "<",
        "gt" => ">",
        "quot" => "\"",
        "apos" => "'",
        "mdash" => "\u{2014}",
        "ndash" => "\u{2013}",
        "hellip" => "\u{2026}",
        "middot" => "\u{00b7}",
        _ => return None,
    };
    Some(mapped.to_string())
}

/// Sanitize card HTML for on-device rendering (e.g. HtmlCompat).
///
/// Keeps formatting tags like <b>/<div>/<font> but removes what a watch can't render: HTML comments
/// (which hide fields like {{Pitch Accent}}), [sound:...] refs, and <img> (media isn't shipped to
/// the watch). Furigana <ruby>base<rt>reading</rt></ruby> becomes base(reading).
#[uniffi::export]
pub fn html_for_display(raw_html: String) -> String {
    let without_scripts = strip_tag_blocks(&raw_html, "script");
    let without_styles = strip_tag_blocks(&without_scripts, "style");
    let without_comments = strip_delimited(&without_styles, "<!--", "-->");
    let without_sound = strip_delimited(&without_comments, "[sound:", "]");
    normalize_ruby(&without_sound).trim().to_string()
}

/// Extract just the answer portion of a card's back.
///
/// Anki emits question + `<hr id=answer>` + the answer, so return everything after that marker tag
/// (or the whole string if it isn't present), which the watch then feeds through
/// [`html_for_display`] / [`clean_html`].
///
/// The marker is usually unquoted (`id=answer`) but some emitters/templates quote it
/// (`id="answer"` / `id='answer'`); accept all three so a quoted marker doesn't silently leave the
/// whole back (question + answer) showing on the watch. Splits at the earliest marker found.
#[uniffi::export]
pub fn answer_html(back: String) -> String {
    let marker =
        ["id=answer", "id=\"answer\"", "id='answer'"].iter().filter_map(|m| back.find(m)).min();
    if let Some(marker) = marker
        && let Some(rel_end) = back[marker..].find('>')
    {
        return back[marker + rel_end + 1..].to_string();
    }
    back
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
