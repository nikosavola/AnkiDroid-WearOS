//! wearos-core: the shared Rust core behind the phone and watch apps. All logic (models, the
//! answer buffer, sync serialization, the review state machine, card-HTML sanitizing, and deck
//! hierarchy) lives in the submodules below and is exported to Kotlin via UniFFI.

uniffi::setup_scaffolding!();

mod db;
mod decks;
mod html;
mod models;
mod session;
mod sync;

// Flatten the crate's Rust surface so tests and any internal callers see everything at the root.
// (UniFFI records each #[uniffi::export] at its definition site, so the module layout doesn't
// affect the generated Kotlin API.)
pub use db::*;
pub use decks::*;
pub use html::*;
pub use models::*;
pub use session::*;
pub use sync::*;

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
    fn test_answer_html() {
        let back = "Question here<hr id=answer>The answer".to_string();
        assert_eq!(answer_html(back), "The answer");

        // Marker with attributes/spacing still splits at its closing '>'.
        let back2 = "Q<hr id=answer class=\"x\">A".to_string();
        assert_eq!(answer_html(back2), "A");

        // No marker: return the whole string unchanged.
        assert_eq!(answer_html("just a back".to_string()), "just a back");
    }

    #[test]
    fn test_strips_script_and_style() {
        // A note type embeds a <script> for click behaviour and a <style> block; neither's source
        // may leak as card text (this was showing "function ClickCard() {...}" on the watch).
        let html =
            "想像<script>function ClickCard() { const x = document.getElementById(\"click\"); \
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
