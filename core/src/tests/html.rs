//! Card-HTML sanitizing and display rendering.

use crate::*;

#[test]
fn test_clean_html() {
    let html = "<p>Hello <b>World</b>!</p><br/>Welcome &nbsp; back &amp; enjoy &lt;3.".to_string();
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
fn test_html_for_display_scales_font_sizes() {
    // Inline px sizes become annotation scales against the 18px bitmap base; unscaled text
    // is left alone. 40/18 = 2.222.
    let out = html_for_display("<div style=\"font-size:40px\">漢</div><div>読み</div>".to_string());
    assert_eq!(
        out,
        "<div style=\"font-size:40px\"><annotation fs=\"2.222\">漢</annotation></div><div>読み</div>"
    );
    // em and % multiply through nesting, and runs flatten to sequential annotations.
    let nested =
        "<div style=\"font-size:2em\"><span style=\"font-size:150%\">x</span>y</div>".to_string();
    assert_eq!(
        html_for_display(nested),
        "<div style=\"font-size:2em\"><span style=\"font-size:150%\">\
            <annotation fs=\"3\">x</annotation></span><annotation fs=\"2\">y</annotation></div>"
    );
    // The legacy font-size attribute ladder (Chrome's, over the same base).
    let legacy = "<font size=\"7\">大</font>".to_string();
    assert_eq!(
        html_for_display(legacy),
        "<font size=\"7\"><annotation fs=\"2.667\">大</annotation></font>"
    );
}

#[test]
fn test_math_renders_as_readable_text() {
    // Both delimiters, structures and symbols; the bitmap path typesets the real thing, this
    // keeps the native fallback and TTS off raw TeX.
    let display = "面積 \\(x^2 + \\frac{a}{b} = \\alpha\\) ?".to_string();
    assert_eq!(clean_html(display), "面積 x^2 + a/b = α ?");
    let block = "\\[E = mc^2\\]".to_string();
    assert_eq!(clean_html(block), "E = mc^2");
    let roots = "<div>\\(\\frac{x+1}{2} \\times \\sqrt{2} \\le 3\\)</div>".to_string();
    assert_eq!(clean_html(roots), "(x+1)/2 × √(2) ≤ 3");
    // Unknown commands fall back to their bare name; display keeps the rest of the markup.
    let back = "Q<hr id=answer>\\(\\unknown{7}\\)".to_string();
    assert_eq!(html_for_display(back), "Q<hr id=answer>unknown7");
    // Unterminated math is left verbatim rather than eating the rest of the card.
    assert_eq!(clean_html("a \\(b".to_string()), "a \\(b");
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

// ---- Rendering fidelity on real-world card HTML (furigana, sentences, hidden fields). ----

#[test]
fn test_clean_html_sentence_with_multiple_furigana() {
    // A sentence with per-kanji furigana (very common in Core2000/Japani cards).
    let html = "<ruby>日<rt>に</rt></ruby><ruby>本<rt>ほん</rt></ruby><ruby>語<rt>ご</rt></ruby>\
                を<ruby>勉<rt>べん</rt></ruby><ruby>強<rt>きょう</rt></ruby>する"
        .to_string();
    assert_eq!(clean_html(html), "日(に)本(ほん)語(ご)を勉(べん)強(きょう)する");
}

#[test]
fn test_clean_html_cloze_front_and_back() {
    // Anki renders cloze to a span; the front shows [...], the back the revealed text. The
    // visible bracket/answer must survive for both.
    let front = "これは<span class=\"cloze\">[...]</span>です".to_string();
    assert_eq!(clean_html(front), "これは[...]です");
    let back = "これは<span class=\"cloze\">ペン</span>です".to_string();
    assert_eq!(clean_html(back), "これはペンです");
}

#[test]
fn test_clean_html_table_cells_are_space_separated() {
    // Some note types lay vocab out in a table; cells must not run together, and rows break.
    let html = "<table><tr><td>語</td><td>word</td></tr>\
                <tr><td>意味</td><td>meaning</td></tr></table>"
        .to_string();
    assert_eq!(clean_html(html), "語 word\n意味 meaning");
}

#[test]
fn test_clean_html_font_and_color_tags_drop_to_text() {
    // Older note types wrap emphasis in <font>/<span style>; TTS/native text keeps just the text.
    let html = "<font color=\"red\"><b>重要</b></font>な<span style=\"color:#00f\">単語</span>"
        .to_string();
    assert_eq!(clean_html(html), "重要な単語");
}

#[test]
fn test_full_back_answer_then_clean_pipeline() {
    // The real watch pipeline for a card back: answer_html (split at the marker) -> clean_html.
    let back = "質問<hr id=answer><div><ruby>答<rt>こた</rt></ruby>え</div>\
                [sound:answer.mp3]"
        .to_string();
    let answer = answer_html(back);
    assert!(!answer.contains("質問"), "question leaked into the answer: {answer}");
    assert_eq!(clean_html(answer), "答(こた)え");
}

#[test]
fn test_html_for_display_pitch_accent_span_survives_for_bitmap_fallback() {
    // html_for_display keeps styled spans (the pitch-accent overline) + furigana, strips the
    // hidden {{Pitch Accent}} comment and img. (Full CSS fidelity is the bitmap path's job; here
    // we just verify nothing user-visible is lost and the hidden field never leaks.)
    let html = "<span style=\"text-decoration:overline\"><ruby>橋<rt>はし</rt></ruby></span>\
                <!-- {{Pitch Accent}} --><img src=\"d.webp\">"
        .to_string();
    let out = html_for_display(html);
    assert!(out.contains("<span"), "styled span dropped: {out}");
    assert!(out.contains("橋(はし)"), "furigana not normalized: {out}");
    assert!(!out.contains("Pitch Accent"), "hidden field leaked: {out}");
    assert!(!out.contains("<img"), "img not stripped: {out}");
}

#[test]
fn test_answer_html() {
    let back = "Question here<hr id=answer>The answer".to_string();
    assert_eq!(answer_html(back), "The answer");

    // Marker with attributes/spacing still splits at its closing '>'.
    let back2 = "Q<hr id=answer class=\"x\">A".to_string();
    assert_eq!(answer_html(back2), "A");

    // Quoted marker variants (double + single) split just like the unquoted form.
    assert_eq!(answer_html("Q<hr id=\"answer\">A".to_string()), "A");
    assert_eq!(answer_html("Q<hr id='answer'>A".to_string()), "A");
    assert_eq!(answer_html("Q<hr class=\"x\" id=\"answer\">A".to_string()), "A");

    // Mixed markers: split at whichever appears first.
    assert_eq!(
        answer_html("Q<hr id=\"answer\">A1<hr id=answer>A2".to_string()),
        "A1<hr id=answer>A2"
    );

    // No marker: return the whole string unchanged.
    assert_eq!(answer_html("just a back".to_string()), "just a back");
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
fn test_clean_html_malformed_and_nested() {
    // Unterminated tag: everything after the stray '<' is swallowed rather than leaking markup.
    assert_eq!(clean_html("before <b broken".to_string()), "before");
    // Unterminated <script> block drops the rest of the input (no source leak).
    assert_eq!(clean_html("keep<script>secret".to_string()), "keep");
    // Nested ruby with an inner formatting tag still yields base(reading).
    assert_eq!(clean_html("<ruby><b>漢</b><rt>かん</rt></ruby>".to_string()), "漢(かん)");
    // Multiple [sound:] refs are all removed.
    assert_eq!(clean_html("a[sound:1.mp3]b[sound:2.mp3]c".to_string()), "abc");
}

#[test]
fn test_clean_html_decodes_entities() {
    // Numeric (decimal + hex) and named references that TTS would otherwise read literally.
    assert_eq!(clean_html("it&#39;s a &quot;test&quot;".to_string()), "it's a \"test\"");
    assert_eq!(clean_html("a&#x2F;b".to_string()), "a/b");
    assert_eq!(clean_html("dash&mdash;dash".to_string()), "dash\u{2014}dash");
    // Single-pass: an escaped entity decodes exactly once, not twice.
    assert_eq!(clean_html("&amp;lt;".to_string()), "&lt;");
    // Unknown/unterminated references are left as-is.
    assert_eq!(clean_html("Q&A".to_string()), "Q&A");
    assert_eq!(clean_html("100&nada".to_string()), "100&nada");
}

#[test]
fn test_clean_html_breaks_block_elements() {
    // Anki wraps lines in <div>s; closing them must break the line for readable text/TTS.
    assert_eq!(
        clean_html("<div>line one</div><div>line two</div>".to_string()),
        "line one\nline two"
    );
    // List items and headers break too.
    assert_eq!(clean_html("<h2>Title</h2><li>a</li><li>b</li>".to_string()), "Title\na\nb");
}

#[test]
fn test_html_for_display_malformed() {
    // A never-closed comment drops everything after it (the {{Pitch Accent}} field can't leak).
    let out = html_for_display("<b>hi</b><!-- {{Pitch Accent}} unterminated".to_string());
    assert!(out.contains("<b>hi</b>"));
    assert!(!out.contains("Pitch Accent"));
    // Case-insensitive <STYLE> blocks are stripped too.
    let styled = html_for_display("keep<STYLE>.c color is red</STYLE>text".to_string());
    assert!(!styled.contains("color is red"));
    assert!(styled.contains("keep") && styled.contains("text"));
}

#[test]
fn test_answer_html_no_false_marker() {
    // A literal "id=answer" appearing inside text (not a tag) still splits at the next '>',
    // documenting the current behaviour so a future change is a conscious one.
    assert_eq!(answer_html("front<hr id=answer>".to_string()), "");
    // Multiple markers: split at the first.
    assert_eq!(answer_html("Q<hr id=answer>A1<hr id=answer>A2".to_string()), "A1<hr id=answer>A2");
}
