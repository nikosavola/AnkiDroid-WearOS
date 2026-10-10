//! The note type's stylesheet, applied to card HTML for the watch's native text renderer, which
//! has no CSS engine. Only class rules and the properties Compose's HTML import can show (colour,
//! size, bold, italic, underline, strike-through) are carried, read the way AnkiDroid's night mode
//! reads them: the body has `card cardN nightMode night_mode`, so `.nightMode .cloze` beats
//! `.cloze`. Rules for the card itself (`.card { color: black }`) are left out, as AnkiDroid's own
//! night mode overrides them.

use std::collections::HashMap;
use std::fmt::Write;

/// The font size the bitmap renderer's page body uses; relative sizes resolve against it.
pub const BASE_PX: f32 = 18.0;

#[derive(Debug, Clone, Default, PartialEq)]
struct Style {
    color: Option<String>,
    /// Absolute px, resolved against the 18px the bitmap renderer's body uses.
    font_size: Option<f32>,
    bold: Option<bool>,
    italic: Option<bool>,
    underline: Option<bool>,
    strike: Option<bool>,
}

impl Style {
    fn merge(&mut self, other: &Self) {
        if other.color.is_some() {
            self.color.clone_from(&other.color);
        }
        if other.font_size.is_some() {
            self.font_size = other.font_size;
        }
        self.bold = other.bold.or(self.bold);
        self.italic = other.italic.or(self.italic);
        self.underline = other.underline.or(self.underline);
        self.strike = other.strike.or(self.strike);
    }

    fn is_empty(&self) -> bool {
        *self == Self::default()
    }
}

struct Rule {
    class: String,
    specificity: usize,
    order: usize,
    style: Style,
}

/// [`html`] with the class rules of [`css`] that apply to card ordinal [`ord`] (0-based) written
/// inline, for renderers without CSS. HTML without class attributes comes back unchanged.
#[uniffi::export]
pub fn inline_card_css(html: String, css: String, ord: u32) -> String {
    if !html.contains("class") {
        return html;
    }
    let rules = parse_rules(&css, ord + 1);
    if rules.is_empty() {
        return html;
    }
    apply(&html, &rules)
}

fn parse_rules(css: &str, card_number: u32) -> Vec<Rule> {
    let css = strip_comments(css);
    let mut rules = Vec::new();
    let mut rest = css.as_str();
    let mut order = 0;
    while let Some(open) = rest.find('{') {
        let selectors = rest[..open].trim();
        let body_start = open + 1;
        if selectors.starts_with('@') {
            // @media, @font-face and friends: skip the whole block, nested braces included.
            let mut depth = 1;
            let mut end = rest.len();
            for (i, c) in rest[body_start..].char_indices() {
                match c {
                    '{' => depth += 1,
                    '}' => {
                        depth -= 1;
                        if depth == 0 {
                            end = body_start + i + 1;
                            break;
                        }
                    }
                    _ => {}
                }
            }
            rest = &rest[end..];
            continue;
        }
        let Some(close) = rest[body_start..].find('}') else {
            break;
        };
        let style = parse_declarations(&rest[body_start..body_start + close]);
        if !style.is_empty() {
            for selector in selectors.split(',') {
                if let Some((class, specificity)) = target_class(selector.trim(), card_number) {
                    rules.push(Rule { class, specificity, order, style: style.clone() });
                    order += 1;
                }
            }
        }
        rest = &rest[body_start + close + 1..];
    }
    rules.sort_by_key(|r| (r.specificity, r.order));
    rules
}

/// The class a selector styles and its specificity, when it is `.x` or `tag.x`, optionally after
/// classes the card body carries (`.nightMode .x`, `.card.night_mode .x`, `.card2 .x`).
fn target_class(selector: &str, card_number: u32) -> Option<(String, usize)> {
    let parts: Vec<&str> = selector.split_whitespace().collect();
    let (target, context) = parts.split_last()?;
    let mut specificity = 1;
    for part in context {
        for class in compound_classes(part)? {
            let on_body = matches!(class, "card" | "nightMode" | "night_mode")
                || class == format!("card{card_number}");
            if !on_body {
                return None;
            }
            specificity += 1;
        }
    }
    let target_classes = compound_classes(target)?;
    let [class] = target_classes.as_slice() else {
        return None;
    };
    // The card itself: AnkiDroid's night mode overrides its colours, and it isn't in our HTML.
    if *class == "card" || class.starts_with("card") && class[4..].parse::<u32>().is_ok() {
        return None;
    }
    let is_plain_name = class.chars().all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_');
    is_plain_name.then(|| (class.to_string(), specificity))
}

/// The classes of a compound selector like `body.card.nightMode` or `.x`, or `None` for anything
/// with ids, attributes, pseudo-classes or combinators.
fn compound_classes(compound: &str) -> Option<Vec<&str>> {
    if compound.contains(['#', '[', ':', '>', '+', '~', '*']) {
        return None;
    }
    let mut pieces = compound.split('.');
    let tag = pieces.next()?;
    if !tag.is_empty() && tag != "body" && !tag.chars().all(|c| c.is_ascii_alphanumeric()) {
        return None;
    }
    let classes: Vec<&str> = pieces.collect();
    if classes.is_empty() || classes.iter().any(|c| c.is_empty()) {
        return None;
    }
    Some(classes)
}

fn parse_declarations(body: &str) -> Style {
    let mut style = Style::default();
    for decl in body.split(';') {
        let Some((prop, value)) = decl.split_once(':') else {
            continue;
        };
        let value = value.trim().trim_end_matches("!important").trim().to_ascii_lowercase();
        match prop.trim().to_ascii_lowercase().as_str() {
            "color" => style.color = normalize_color(&value),
            "font-size" => style.font_size = font_size_px(&value),
            "font-weight" => {
                style.bold = Some(
                    value == "bold"
                        || value == "bolder"
                        || value.parse::<u32>().is_ok_and(|w| w >= 600),
                );
            }
            "font-style" => style.italic = Some(value == "italic" || value == "oblique"),
            "text-decoration" | "text-decoration-line" => {
                style.underline = Some(value.contains("underline"));
                style.strike = Some(value.contains("line-through"));
            }
            _ => {}
        }
    }
    style
}

/// A font size in px, or `None` for values the watch can't honour.
///
/// `em` and `%` resolve against the 18px base the bitmap renderer's body uses, matching how the
/// class rule sizes text there. A note CSS that sets its own `.card` font-size shifts the WebView's
/// real base, so those decks' sizes can be a few percent off; the ladder keywords follow Chrome.
pub fn font_size_px(value: &str) -> Option<f32> {
    let lower = value.to_ascii_lowercase();
    let number = |s: &str| s.trim().parse::<f32>().ok().filter(|v| v.is_finite() && *v > 0.0);
    if let Some(rest) = lower.strip_suffix("px") {
        return number(rest);
    }
    if let Some(rest) = lower.strip_suffix("pt") {
        return number(rest).map(|v| v * 4.0 / 3.0);
    }
    if let Some(rest) = lower.strip_suffix("em") {
        return number(rest).map(|v| v * BASE_PX);
    }
    if let Some(rest) = lower.strip_suffix('%') {
        return number(rest).map(|v| v * BASE_PX / 100.0);
    }
    // Chrome's CSS keyword ladder.
    let px = match lower.trim() {
        "xx-small" => 10.0,
        "x-small" => 13.0,
        "small" => 16.0,
        "medium" => 18.0,
        "large" => 24.0,
        "x-large" => 32.0,
        "xx-large" => 48.0,
        _ => return None,
    };
    Some(px)
}

/// A colour as `#rrggbb`, which every HTML importer understands, or `None` if it isn't one.
fn normalize_color(value: &str) -> Option<String> {
    if let Some(hex) = value.strip_prefix('#') {
        let full: String = match hex.len() {
            3 | 4 => hex.chars().take(3).flat_map(|c| [c, c]).collect(),
            6 | 8 => hex[..6].to_string(),
            _ => return None,
        };
        return full.chars().all(|c| c.is_ascii_hexdigit()).then(|| format!("#{full}"));
    }
    if let Some(args) = value.strip_prefix("rgba(").or_else(|| value.strip_prefix("rgb(")) {
        let channels: Vec<u8> = args
            .trim_end_matches(')')
            .split([',', ' ', '/'])
            .filter(|s| !s.is_empty())
            .take(3)
            .map(|s| s.parse::<f32>().ok().map(|v| v.clamp(0.0, 255.0) as u8))
            .collect::<Option<_>>()?;
        let [r, g, b] = channels.as_slice() else {
            return None;
        };
        return Some(format!("#{r:02x}{g:02x}{b:02x}"));
    }
    named_color(value).map(str::to_string)
}

pub const VOID_ELEMENTS: &[&str] = &[
    "area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "source", "track",
    "wbr",
];

fn apply(html: &str, rules: &[Rule]) -> String {
    let by_class: HashMap<&str, Vec<&Rule>> = rules.iter().fold(HashMap::new(), |mut map, rule| {
        map.entry(rule.class.as_str()).or_insert_with(Vec::new).push(rule);
        map
    });
    let mut out = String::with_capacity(html.len());
    // Open elements and the wrapper tags to close with each.
    let mut stack: Vec<(String, Vec<&'static str>)> = Vec::new();
    let mut rest = html;
    while let Some(lt) = rest.find('<') {
        out.push_str(&rest[..lt]);
        let Some(gt) = rest[lt..].find('>') else {
            out.push_str(&rest[lt..]);
            return out;
        };
        let tag = &rest[lt..=lt + gt];
        rest = &rest[lt + gt + 1..];
        let inner = &tag[1..tag.len() - 1];
        if let Some(name) = inner.strip_prefix('/') {
            let name = name.trim().to_ascii_lowercase();
            if let Some(pos) = stack.iter().rposition(|(open, _)| *open == name) {
                for (_, wrappers) in stack.drain(pos..).rev() {
                    for w in wrappers.iter().rev() {
                        let _ = write!(out, "</{w}>");
                    }
                }
            }
            out.push_str(tag);
            continue;
        }
        let name = inner
            .split(|c: char| c.is_whitespace() || c == '/')
            .next()
            .unwrap_or("")
            .to_ascii_lowercase();
        if inner.starts_with('!') || name.is_empty() {
            out.push_str(tag);
            continue;
        }
        let style = class_attr(inner).map(|classes| {
            let mut matched: Vec<&Rule> = classes
                .split_whitespace()
                .filter_map(|c| by_class.get(c))
                .flatten()
                .copied()
                .collect();
            matched.sort_by_key(|r| (r.specificity, r.order));
            matched.iter().fold(Style::default(), |mut s, r| {
                s.merge(&r.style);
                s
            })
        });
        let self_closing = inner.ends_with('/') || VOID_ELEMENTS.contains(&name.as_str());
        match style.filter(|s| !s.is_empty()) {
            Some(style) if !self_closing => {
                out.push_str(&with_inline_style(tag, style.color.as_deref(), style.font_size));
                let wrappers: Vec<&'static str> = [
                    (style.bold == Some(true), "b"),
                    (style.italic == Some(true), "i"),
                    (style.underline == Some(true), "u"),
                    (style.strike == Some(true), "s"),
                ]
                .into_iter()
                .filter_map(|(on, w)| on.then_some(w))
                .collect();
                for w in &wrappers {
                    let _ = write!(out, "<{w}>");
                }
                stack.push((name, wrappers));
            }
            _ => {
                out.push_str(tag);
                if !self_closing {
                    stack.push((name, Vec::new()));
                }
            }
        }
    }
    out.push_str(rest);
    out
}

/// The value of a tag's `class` attribute, quoted or not.
fn class_attr(inner: &str) -> Option<&str> {
    crate::util::attr_value(inner, "class")
}

/// [`tag`] with `color` and `font-size` added to its inline style, each skipped when the element
/// already sets its own. `font-size` lands in `style=` so the watch's display pass can scale it.
fn with_inline_style(tag: &str, color: Option<&str>, font_size: Option<f32>) -> String {
    let mut color = color.map(|c| format!("color:{c}"));
    let mut font_size = font_size.map(|px| format!("font-size:{px}px"));
    if color.is_none() && font_size.is_none() {
        return tag.to_string();
    }
    let lower = tag.to_ascii_lowercase();
    if let Some(at) = lower.find("style=") {
        let quote_at = at + "style=".len();
        let Some(q @ ('"' | '\'')) = tag[quote_at..].chars().next() else {
            return tag.to_string();
        };
        let value_start = quote_at + 1;
        let Some(len) = tag[value_start..].find(q) else {
            return tag.to_string();
        };
        let existing = &tag[value_start..value_start + len];
        for decl in existing.split(';') {
            let prop =
                decl.split_once(':').map_or(String::new(), |(p, _)| p.trim().to_ascii_lowercase());
            match prop.as_str() {
                "color" => color = None,
                "font-size" => font_size = None,
                _ => {}
            }
        }
        let additions: Vec<String> = [color, font_size].into_iter().flatten().collect();
        if additions.is_empty() {
            return tag.to_string();
        }
        let prefix = if existing.trim().is_empty() {
            String::new()
        } else {
            format!("{};", existing.trim_end_matches(';'))
        };
        return format!(
            "{}{}{}{}",
            &tag[..value_start],
            prefix,
            additions.join(";"),
            &tag[value_start + len..]
        );
    }
    let end = if tag.ends_with("/>") { tag.len() - 2 } else { tag.len() - 1 };
    let joined = [color, font_size].into_iter().flatten().collect::<Vec<_>>().join(";");
    format!("{} style=\"{}\"{}", &tag[..end], joined, &tag[end..])
}

fn strip_comments(css: &str) -> String {
    let mut out = String::with_capacity(css.len());
    let mut rest = css;
    while let Some(start) = rest.find("/*") {
        out.push_str(&rest[..start]);
        match rest[start + 2..].find("*/") {
            Some(end) => rest = &rest[start + 2 + end + 2..],
            None => return out,
        }
    }
    out.push_str(rest);
    out
}

/// CSS named colours.
const NAMED_COLORS: &[(&str, &str)] = &[
    ("aliceblue", "#f0f8ff"),
    ("antiquewhite", "#faebd7"),
    ("aqua", "#00ffff"),
    ("cyan", "#00ffff"),
    ("aquamarine", "#7fffd4"),
    ("azure", "#f0ffff"),
    ("beige", "#f5f5dc"),
    ("bisque", "#ffe4c4"),
    ("black", "#000000"),
    ("blanchedalmond", "#ffebcd"),
    ("blue", "#0000ff"),
    ("blueviolet", "#8a2be2"),
    ("brown", "#a52a2a"),
    ("burlywood", "#deb887"),
    ("cadetblue", "#5f9ea0"),
    ("chartreuse", "#7fff00"),
    ("chocolate", "#d2691e"),
    ("coral", "#ff7f50"),
    ("cornflowerblue", "#6495ed"),
    ("cornsilk", "#fff8dc"),
    ("crimson", "#dc143c"),
    ("darkblue", "#00008b"),
    ("darkcyan", "#008b8b"),
    ("darkgoldenrod", "#b8860b"),
    ("darkgray", "#a9a9a9"),
    ("darkgrey", "#a9a9a9"),
    ("darkgreen", "#006400"),
    ("darkkhaki", "#bdb76b"),
    ("darkmagenta", "#8b008b"),
    ("darkolivegreen", "#556b2f"),
    ("darkorange", "#ff8c00"),
    ("darkorchid", "#9932cc"),
    ("darkred", "#8b0000"),
    ("darksalmon", "#e9967a"),
    ("darkseagreen", "#8fbc8f"),
    ("darkslateblue", "#483d8b"),
    ("darkslategray", "#2f4f4f"),
    ("darkslategrey", "#2f4f4f"),
    ("darkturquoise", "#00ced1"),
    ("darkviolet", "#9400d3"),
    ("deeppink", "#ff1493"),
    ("deepskyblue", "#00bfff"),
    ("dimgray", "#696969"),
    ("dimgrey", "#696969"),
    ("dodgerblue", "#1e90ff"),
    ("firebrick", "#b22222"),
    ("floralwhite", "#fffaf0"),
    ("forestgreen", "#228b22"),
    ("fuchsia", "#ff00ff"),
    ("magenta", "#ff00ff"),
    ("gainsboro", "#dcdcdc"),
    ("ghostwhite", "#f8f8ff"),
    ("gold", "#ffd700"),
    ("goldenrod", "#daa520"),
    ("gray", "#808080"),
    ("grey", "#808080"),
    ("green", "#008000"),
    ("greenyellow", "#adff2f"),
    ("honeydew", "#f0fff0"),
    ("hotpink", "#ff69b4"),
    ("indianred", "#cd5c5c"),
    ("indigo", "#4b0082"),
    ("ivory", "#fffff0"),
    ("khaki", "#f0e68c"),
    ("lavender", "#e6e6fa"),
    ("lavenderblush", "#fff0f5"),
    ("lawngreen", "#7cfc00"),
    ("lemonchiffon", "#fffacd"),
    ("lightblue", "#add8e6"),
    ("lightcoral", "#f08080"),
    ("lightcyan", "#e0ffff"),
    ("lightgoldenrodyellow", "#fafad2"),
    ("lightgray", "#d3d3d3"),
    ("lightgrey", "#d3d3d3"),
    ("lightgreen", "#90ee90"),
    ("lightpink", "#ffb6c1"),
    ("lightsalmon", "#ffa07a"),
    ("lightseagreen", "#20b2aa"),
    ("lightskyblue", "#87cefa"),
    ("lightslategray", "#778899"),
    ("lightslategrey", "#778899"),
    ("lightsteelblue", "#b0c4de"),
    ("lightyellow", "#ffffe0"),
    ("lime", "#00ff00"),
    ("limegreen", "#32cd32"),
    ("linen", "#faf0e6"),
    ("maroon", "#800000"),
    ("mediumaquamarine", "#66cdaa"),
    ("mediumblue", "#0000cd"),
    ("mediumorchid", "#ba55d3"),
    ("mediumpurple", "#9370db"),
    ("mediumseagreen", "#3cb371"),
    ("mediumslateblue", "#7b68ee"),
    ("mediumspringgreen", "#00fa9a"),
    ("mediumturquoise", "#48d1cc"),
    ("mediumvioletred", "#c71585"),
    ("midnightblue", "#191970"),
    ("mintcream", "#f5fffa"),
    ("mistyrose", "#ffe4e1"),
    ("moccasin", "#ffe4b5"),
    ("navajowhite", "#ffdead"),
    ("navy", "#000080"),
    ("oldlace", "#fdf5e6"),
    ("olive", "#808000"),
    ("olivedrab", "#6b8e23"),
    ("orange", "#ffa500"),
    ("orangered", "#ff4500"),
    ("orchid", "#da70d6"),
    ("palegoldenrod", "#eee8aa"),
    ("palegreen", "#98fb98"),
    ("paleturquoise", "#afeeee"),
    ("palevioletred", "#db7093"),
    ("papayawhip", "#ffefd5"),
    ("peachpuff", "#ffdab9"),
    ("peru", "#cd853f"),
    ("pink", "#ffc0cb"),
    ("plum", "#dda0dd"),
    ("powderblue", "#b0e0e6"),
    ("purple", "#800080"),
    ("rebeccapurple", "#663399"),
    ("red", "#ff0000"),
    ("rosybrown", "#bc8f8f"),
    ("royalblue", "#4169e1"),
    ("saddlebrown", "#8b4513"),
    ("salmon", "#fa8072"),
    ("sandybrown", "#f4a460"),
    ("seagreen", "#2e8b57"),
    ("seashell", "#fff5ee"),
    ("sienna", "#a0522d"),
    ("silver", "#c0c0c0"),
    ("skyblue", "#87ceeb"),
    ("slateblue", "#6a5acd"),
    ("slategray", "#708090"),
    ("slategrey", "#708090"),
    ("snow", "#fffafa"),
    ("springgreen", "#00ff7f"),
    ("steelblue", "#4682b4"),
    ("tan", "#d2b48c"),
    ("teal", "#008080"),
    ("thistle", "#d8bfd8"),
    ("tomato", "#ff6347"),
    ("turquoise", "#40e0d0"),
    ("violet", "#ee82ee"),
    ("wheat", "#f5deb3"),
    ("white", "#ffffff"),
    ("whitesmoke", "#f5f5f5"),
    ("yellow", "#ffff00"),
    ("yellowgreen", "#9acd32"),
];

fn named_color(name: &str) -> Option<&'static str> {
    NAMED_COLORS.iter().find(|(n, _)| *n == name).map(|(_, hex)| *hex)
}

#[cfg(test)]
mod tests {
    use super::*;

    // Anki's stock Cloze stylesheet.
    const CLOZE_CSS: &str = ".card {\n font-family: arial;\n font-size: 20px;\n text-align: center;\n \
        color: black;\n background-color: white;\n}\n.cloze {\n font-weight: bold;\n color: blue;\n}\n\
        .nightMode .cloze {\n color: lightblue;\n}";

    #[test]
    fn stock_cloze_reads_like_ankidroid_night_mode() {
        let html = r#"<span class="cloze" data-ordinal="1">[...]</span> is in <span class="cloze-inactive" data-ordinal="2">France</span>"#;
        assert_eq!(
            inline_card_css(html.to_string(), CLOZE_CSS.to_string(), 0),
            r#"<span class="cloze" data-ordinal="1" style="color:#add8e6"><b>[...]</b></span> is in <span class="cloze-inactive" data-ordinal="2">France</span>"#
        );
    }

    #[test]
    fn card_rules_and_unsupported_selectors_are_ignored() {
        let css = ".card { color: black } #x { color: red } .a > .b { color: red } .a:hover { color: red }";
        let html = r#"<div class="card a b" id="x">t</div>"#;
        assert_eq!(inline_card_css(html.into(), css.into(), 0), html);
    }

    #[test]
    fn card_number_scoped_rules_apply_only_to_their_card() {
        let css = ".card2 .hint { color: #f00 } .hint { font-style: italic }";
        let html = "<i class=hint>h</i>".to_string();
        assert_eq!(inline_card_css(html.clone(), css.into(), 0), "<i class=hint><i>h</i></i>");
        assert_eq!(
            inline_card_css(html, css.into(), 1),
            r#"<i class=hint style="color:#ff0000"><i>h</i></i>"#
        );
    }

    #[test]
    fn an_elements_own_inline_colour_wins() {
        let css = ".x { color: red }";
        let html = r#"<span class="x" style="color: green">t</span>"#;
        assert_eq!(inline_card_css(html.into(), css.into(), 0), html);
        let other = r#"<span class="x" style="font-size:2em">t</span>"#;
        assert_eq!(
            inline_card_css(other.into(), css.into(), 0),
            r#"<span class="x" style="font-size:2em;color:#ff0000">t</span>"#
        );
        // The element's own font-size wins over the class rule's, like its own colour does.
        let sized = ".k { font-size: 40px; color: blue }";
        assert_eq!(
            inline_card_css(
                r#"<span class="k" style="font-size:20px">漢</span>"#.into(),
                sized.into(),
                0
            ),
            r#"<span class="k" style="font-size:20px;color:#0000ff">漢</span>"#
        );
    }

    #[test]
    fn font_size_rules_are_inlined_as_px() {
        let css = ".kanji { font-size: 40px } .big { font-size: 2em } .pct { font-size: 150% }";
        let html = r#"<span class="kanji">漢</span><span class="big">大</span><span class="pct">字</span>"#;
        assert_eq!(
            inline_card_css(html.into(), css.into(), 0),
            concat!(
                r#"<span class="kanji" style="font-size:40px">漢</span>"#,
                r#"<span class="big" style="font-size:36px">大</span>"#,
                r#"<span class="pct" style="font-size:27px">字</span>"#,
            )
        );
    }

    #[test]
    fn wrappers_close_with_their_element_through_nesting() {
        let css = ".w { font-weight: 700; text-decoration: underline line-through }";
        let html = r#"<div class="w">a<div>b<br>c</div>d</div>e"#;
        assert_eq!(
            inline_card_css(html.into(), css.into(), 0),
            r#"<div class="w"><b><u><s>a<div>b<br>c</div>d</s></u></b></div>e"#
        );
    }

    #[test]
    fn media_blocks_and_comments_are_skipped() {
        let css = "/* .x { color: red } */ @media (max-width: 1px) { .x { color: red } } .x { color: #0f0a }";
        assert_eq!(
            inline_card_css("<b class=x>t</b>".into(), css.into(), 0),
            r#"<b class=x style="color:#00ff00">t</b>"#
        );
    }

    #[test]
    fn colours_normalize_to_hex() {
        assert_eq!(normalize_color("rgb(255, 0, 128)").as_deref(), Some("#ff0080"));
        assert_eq!(normalize_color("rgba(1 2 3 / 50%)").as_deref(), Some("#010203"));
        assert_eq!(normalize_color("#ABC").as_deref(), Some("#AABBCC"));
        assert_eq!(normalize_color("var(--fg)"), None);
        assert_eq!(
            normalize_color("CornflowerBlue".to_ascii_lowercase().as_str()).as_deref(),
            Some("#6495ed")
        );
    }

    #[test]
    fn html_without_classes_is_untouched() {
        let html = "<b>plain</b>".to_string();
        assert_eq!(inline_card_css(html.clone(), CLOZE_CSS.into(), 0), html);
    }
}
