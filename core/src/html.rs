//! Card HTML sanitizing for on-device rendering and TTS. The watch has no WebView, so these turn
//! AnkiDroid's compiled card HTML into either plain text (TTS) or a HtmlCompat-safe subset (display).

use std::fmt::Write as _;

use crate::css::{BASE_PX, VOID_ELEMENTS};
use crate::util::attr_value;

/// Consume a tag from `chars` (which sits just past `<`) up to and including `>`: returns the raw
/// tag inside, whether it's a closing tag, and its ASCII-lowercased name. An unterminated tag
/// consumes the rest of the input.
fn scan_tag(chars: &mut std::iter::Peekable<std::str::Chars<'_>>) -> (String, bool, String) {
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
    (tag, closing, name)
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
    let without_math = plain_math(&without_sound);

    // 2. Convert structural HTML blocks to newlines.
    let result = without_math
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
            let (_, closing, name) = scan_tag(&mut chars);
            match name.as_str() {
                "rt" => clean.push(if closing { ')' } else { '(' }),
                "rp" => in_rp = !closing,
                // Table cells: separate with a space so a two-column vocab row reads "語 reading",
                // not "語reading". The row itself breaks on </tr> below.
                "td" | "th" if closing => clean.push(' '),
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

/// Decode HTML character references in `s`: named and numeric.
///
/// Single-pass — an already-escaped `&amp;lt;` decodes to `&lt;`, not `<`. Unknown or unterminated
/// references are left verbatim.
pub fn decode_entities(s: &str) -> String {
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
    let without_math = plain_math(&without_sound);
    annotate_font_sizes(&normalize_ruby(&without_math)).trim().to_string()
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

/// Wrap card text runs in `<annotation fs=...>` tags carrying each element's effective font-size
/// scale, for the watch's native renderer: Android's HTML import drops font-size styles, but
/// Compose turns `<annotation key=value>` into string annotations, which the watch converts to
/// font-size spans. The converter ends every open annotation at the first closing tag it sees, so
/// annotations must never nest or overlap: at most one is open at a time, closed before any tag
/// and reopened around the next text run that needs a scale. Scales are relative to the 18px the
/// bitmap page's body uses, so the two render paths size the same card alike.
// effective == 1.0 is exact by construction (the unscaled root), so plain == is safe here.
#[expect(clippy::float_cmp, reason = "the unscaled root scale is exactly 1.0")]
fn annotate_font_sizes(html: &str) -> String {
    let lower = html.to_ascii_lowercase();
    if !lower.contains("font-size") && !lower.contains("<font") {
        return html.to_string();
    }
    let mut out = String::with_capacity(html.len() + 64);
    // (element name, effective scale); the root entry is the unscaled card.
    let mut stack: Vec<(String, f32)> = vec![(String::new(), 1.0)];
    let mut open: Option<f32> = None;
    let mut rest = html;
    while let Some(lt) = rest.find('<') {
        let text = &rest[..lt];
        let effective = stack.last().map_or(1.0, |(_, s)| *s);
        if effective != 1.0 && open != Some(effective) && text.chars().any(|c| !c.is_whitespace()) {
            open_annotation(&mut out, &mut open, effective);
        }
        out.push_str(text);
        let Some(gt) = rest[lt..].find('>') else {
            out.push_str(&rest[lt..]);
            rest = "";
            break;
        };
        let tag = &rest[lt..=lt + gt];
        rest = &rest[lt + gt + 1..];
        // Every tag closes the annotation first: tags never sit inside one, so no annotation can
        // ever straddle a point where another would have to open.
        if open.take().is_some() {
            out.push_str("</annotation>");
        }
        out.push_str(tag);
        let inner = &tag[1..tag.len() - 1];
        let name = inner
            .trim_start_matches('/')
            .split(|c: char| c.is_whitespace())
            .next()
            .unwrap_or("")
            .to_ascii_lowercase();
        if inner.starts_with('!') || name.is_empty() {
            continue;
        }
        if inner.starts_with('/') {
            if let Some(pos) = stack.iter().rposition(|(open_name, _)| *open_name == name) {
                stack.truncate(pos);
            }
        } else {
            let parent = stack.last().map_or(1.0, |(_, s)| *s);
            let factor = tag_font_factor(inner, &name).unwrap_or(1.0);
            let self_closing = inner.ends_with('/') || VOID_ELEMENTS.contains(&name.as_str());
            if !self_closing {
                stack.push((name, parent * factor));
            }
        }
    }
    let effective = stack.last().map_or(1.0, |(_, s)| *s);
    if effective != 1.0 && open != Some(effective) && rest.chars().any(|c| !c.is_whitespace()) {
        open_annotation(&mut out, &mut open, effective);
    }
    out.push_str(rest);
    if open.is_some() {
        out.push_str("</annotation>");
    }
    out
}

/// Close any open annotation and open a new one at [effective]. Tags never sit inside one, so no
/// annotation can straddle a point where another would have to open.
fn open_annotation(out: &mut String, open: &mut Option<f32>, effective: f32) {
    if open.take().is_some() {
        out.push_str("</annotation>");
    }
    *open = Some(effective);
    let _ = write!(out, "<annotation fs=\"{}\">", format_scale(effective));
}

/// A scale factor without trailing zeros: 2.0 -> "2", 2.2222 -> "2.222".
fn format_scale(v: f32) -> String {
    format!("{v:.3}").trim_end_matches('0').trim_end_matches('.').to_string()
}

/// The font-size factor an opening tag imposes on its parent: the `style` attribute's font-size,
/// or the legacy `<font size=N>` ladder. `None` when the tag carries no size.
fn tag_font_factor(inner: &str, name: &str) -> Option<f32> {
    if let Some(style) = attr_value(inner, "style")
        && let Some((_, value)) = style.split(';').find_map(|d| {
            d.split_once(':').filter(|(p, _)| p.trim().eq_ignore_ascii_case("font-size"))
        })
    {
        return crate::css::font_size_px(value).map(|px| px / BASE_PX);
    }
    if name == "font"
        && let Some(size) = attr_value(inner, "size")
        && let Some(n) = size.trim().parse::<u32>().ok()
        && let Some(px) = [10.0, 13.0, 16.0, 18.0, 24.0, 32.0, 48.0].get(n.checked_sub(1)? as usize)
    {
        return Some(px / BASE_PX);
    }
    None
}

/// Turn Anki's MathJax spans (`\(...\)`, `\[...\]`) into readable text. The bitmap path typesets
/// real math; this only keeps the native fallback and TTS from showing raw TeX.
fn plain_math(s: &str) -> String {
    if !s.contains("\\(") && !s.contains("\\[") {
        return s.to_string();
    }
    let mut out = String::with_capacity(s.len());
    let mut rest = s;
    loop {
        let open = [rest.find("\\("), rest.find("\\[")].into_iter().flatten().min();
        let Some(start) = open else {
            out.push_str(rest);
            return out;
        };
        let (close, open_len) =
            if rest[start..].starts_with("\\(") { ("\\)", 2) } else { ("\\]", 2) };
        out.push_str(&rest[..start]);
        let body_start = start + open_len;
        let Some(body_end) = rest[body_start..].find(close) else {
            out.push_str(&rest[start..]);
            return out;
        };
        out.push_str(&tex_to_text(&rest[body_start..body_start + body_end]));
        rest = &rest[body_start + body_end + 2..];
    }
}

/// TeX source as readable text: fractions and roots restructured, the common symbols as
/// characters, and the remaining backslash commands as their bare names.
fn tex_to_text(tex: &str) -> String {
    let mut out = String::with_capacity(tex.len());
    let chars: Vec<char> = tex.chars().collect();
    let brace_group = |at: &mut usize| -> String {
        // *at is on the '{'; returns the group's content and leaves *at past the closing '}'.
        if chars.get(*at) != Some(&'{') {
            return String::new();
        }
        let mut depth = 0;
        let mut group = String::new();
        while let Some(&c) = chars.get(*at) {
            *at += 1;
            match c {
                '{' => depth += 1,
                '}' => {
                    depth -= 1;
                    if depth == 0 {
                        break;
                    }
                }
                _ => group.push(c),
            }
        }
        group
    };
    let mut i = 0;
    while let Some(&c) = chars.get(i) {
        match c {
            '\\' => {
                i += 1;
                match chars.get(i) {
                    None => {}
                    Some(&next) if next.is_alphabetic() => {
                        let start = i;
                        while chars.get(i).is_some_and(|&c| c.is_alphabetic()) {
                            i += 1;
                        }
                        let word: String = chars[start..i].iter().collect();
                        match word.as_str() {
                            "frac" => {
                                let num = tex_to_text(&brace_group(&mut i));
                                let den = tex_to_text(&brace_group(&mut i));
                                let wrap = |g: &str| {
                                    if g.chars()
                                        .any(|c| ['+', '-', '=', '/', '<', '>'].contains(&c))
                                    {
                                        format!("({g})")
                                    } else {
                                        g.to_string()
                                    }
                                };
                                let _ = write!(out, "{}/{}", wrap(&num), wrap(&den));
                            }
                            "sqrt" => {
                                let group = tex_to_text(&brace_group(&mut i));
                                let _ = write!(out, "√({group})");
                            }
                            "begin" | "end" => {
                                brace_group(&mut i);
                            }
                            "left" | "right" => {}
                            _ => out.push_str(
                                MATH_SYMBOLS
                                    .iter()
                                    .find(|(name, _)| *name == word)
                                    .map_or(word.as_str(), |(_, ch)| ch),
                            ),
                        }
                    }
                    Some('\\') => {
                        out.push(' ');
                        i += 1;
                    }
                    Some(&next) if [',', ';', '!', ' ', ':'].contains(&next) => {
                        out.push(' ');
                        i += 1;
                    }
                    Some(&next) => {
                        out.push(next);
                        i += 1;
                    }
                }
            }
            '^' | '_' => {
                out.push(c);
                i += 1;
                if chars.get(i) == Some(&'{') {
                    i += 1;
                    out.push_str(&tex_to_text(&brace_group(&mut i)));
                } else if let Some(&next) = chars.get(i) {
                    out.push(next);
                    i += 1;
                }
            }
            '{' | '}' => i += 1,
            _ => {
                out.push(c);
                i += 1;
            }
        }
    }
    out
}

/// The TeX commands that map to a single character; everything else renders as its bare name.
const MATH_SYMBOLS: &[(&str, &str)] = &[
    ("alpha", "α"),
    ("beta", "β"),
    ("gamma", "γ"),
    ("delta", "δ"),
    ("Delta", "Δ"),
    ("epsilon", "ε"),
    ("zeta", "ζ"),
    ("eta", "η"),
    ("theta", "θ"),
    ("Theta", "Θ"),
    ("iota", "ι"),
    ("kappa", "κ"),
    ("lambda", "λ"),
    ("Lambda", "Λ"),
    ("mu", "μ"),
    ("nu", "ν"),
    ("xi", "ξ"),
    ("pi", "π"),
    ("Pi", "Π"),
    ("rho", "ρ"),
    ("sigma", "σ"),
    ("Sigma", "Σ"),
    ("tau", "τ"),
    ("phi", "φ"),
    ("Phi", "Φ"),
    ("chi", "χ"),
    ("psi", "ψ"),
    ("omega", "ω"),
    ("Omega", "Ω"),
    ("times", "×"),
    ("cdot", "·"),
    ("div", "÷"),
    ("pm", "±"),
    ("mp", "∓"),
    ("le", "≤"),
    ("ge", "≥"),
    ("ne", "≠"),
    ("approx", "≈"),
    ("equiv", "≡"),
    ("infty", "∞"),
    ("sum", "Σ"),
    ("prod", "Π"),
    ("int", "∫"),
    ("partial", "∂"),
    ("in", "∈"),
    ("notin", "∉"),
    ("rightarrow", "→"),
    ("to", "→"),
    ("leftarrow", "←"),
    ("Rightarrow", "⇒"),
    ("ldots", "…"),
    ("cdots", "⋯"),
    ("circ", "∘"),
    ("emptyset", "∅"),
    ("cup", "∪"),
    ("cap", "∩"),
    ("subset", "⊂"),
    ("supset", "⊃"),
    ("neg", "¬"),
];

/// Drop ruby wrapper tags and <img>, render <rt> as parenthesized readings, and discard <rp>
/// fallbacks, while passing every other tag through unchanged.
fn normalize_ruby(s: &str) -> String {
    let mut out = String::new();
    let mut chars = s.chars().peekable();
    let mut in_rp = false;
    while let Some(c) = chars.next() {
        if c == '<' {
            let (tag, closing, name) = scan_tag(&mut chars);
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
