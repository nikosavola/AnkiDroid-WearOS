//! wearos-core: the shared Rust core behind the phone and watch apps. All logic (models, the
//! answer buffer, sync serialization, the review state machine, card-HTML sanitizing, and deck
//! hierarchy) lives in the submodules below and is exported to Kotlin via UniFFI.

uniffi::setup_scaffolding!();

mod async_ops;
mod db;
mod decks;
mod html;
mod models;
mod proto;
mod session;
mod sync;
mod tile;

// Flatten the crate's Rust surface so tests and any internal callers see everything at the root.
// (UniFFI records each #[uniffi::export] at its definition site, so the module layout doesn't
// affect the generated Kotlin API.)
pub use async_ops::*;
pub use db::*;
pub use decks::*;
pub use html::*;
pub use models::*;
pub use proto::*;
pub use session::*;
pub use sync::*;
pub use tile::*;

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::{Mutex, MutexGuard};

    // The buffer tests all drive the single global DB_CONN, so serialize them; cargo runs tests in
    // parallel by default. Recover from poisoning so one failing test doesn't cascade into the rest.
    static DB_TEST_LOCK: Mutex<()> = Mutex::new(());
    fn db_test_guard() -> MutexGuard<'static, ()> {
        DB_TEST_LOCK.lock().unwrap_or_else(std::sync::PoisonError::into_inner)
    }

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

    fn card_with_front(front: &str) -> WearosCard {
        WearosCard {
            id: 1,
            note_id: 2,
            ord: 0,
            front: front.to_string(),
            back: "A".to_string(),
            button_count: 4,
            next_review_times: vec!["1m".to_string()],
        }
    }

    #[test]
    fn test_due_batch_is_gzipped_and_smaller() {
        // A repetitive payload must come back gzip-framed and meaningfully smaller than the JSON.
        let front = "<b>repeated content </b>".repeat(200);
        let json_len = serde_json::to_vec(&card_with_front(&front)).unwrap().len();
        let batch = WearosDueBatch {
            deck_id: 1,
            deck_name: "D".to_string(),
            cards: vec![card_with_front(&front)],
        };
        let bytes = serialize_due_batch(batch).unwrap();
        assert_eq!(&bytes[..2], &[0x1f, 0x8b], "payload is not gzip-framed");
        assert!(
            bytes.len() < json_len,
            "gzip did not shrink the payload: {} vs {json_len}",
            bytes.len()
        );
    }

    #[test]
    fn test_deserialize_due_batch_accepts_raw_json() {
        // Backward compatibility: an item left by a pre-compression peer is uncompressed JSON and
        // must still deserialize (maybe_gunzip passes non-gzip bytes straight through).
        let batch = WearosDueBatch {
            deck_id: 7,
            deck_name: "Raw".to_string(),
            cards: vec![card_with_front("Q")],
        };
        let raw_json = serde_json::to_vec(&batch).unwrap();
        assert_ne!(&raw_json[..2], &[0x1f, 0x8b]);
        let restored = deserialize_due_batch(raw_json).unwrap();
        assert_eq!(restored.deck_id, 7);
        assert_eq!(restored.cards[0].front, "Q");
    }

    #[test]
    fn test_deserialize_due_batch_rejects_garbage() {
        // Neither gzip nor JSON: a clear SerializationError, not a panic. (WearosDueBatch isn't
        // Debug, so match the result rather than unwrap_err.)
        let result = deserialize_due_batch(vec![b'n', b'o', b'p', b'e']);
        assert!(matches!(result, Err(CoreError::SerializationError { .. })));
    }

    #[test]
    fn test_due_batch_proto_round_trips() {
        let batch = WearosDueBatch {
            deck_id: 55,
            deck_name: "文法".to_string(),
            cards: vec![card_with_front("<b>Q</b>"), card_with_front("Q2")],
        };
        let bytes = serialize_due_batch_proto(batch);
        let restored = deserialize_due_batch_proto(bytes).unwrap();
        assert_eq!(restored.deck_id, 55);
        assert_eq!(restored.deck_name, "文法");
        assert_eq!(restored.cards.len(), 2);
        assert_eq!(restored.cards[0].front, "<b>Q</b>");
        assert_eq!(restored.cards[1].front, "Q2");
        assert_eq!(restored.cards[0].next_review_times, vec!["1m".to_string()]);
    }

    #[test]
    fn test_due_batch_proto_smaller_than_raw_json() {
        // Protobuf drops the JSON field-name overhead; on a many-card batch it beats raw JSON.
        let cards: Vec<WearosCard> = (0..50).map(|_| card_with_front("some front text")).collect();
        let batch = WearosDueBatch { deck_id: 1, deck_name: "D".to_string(), cards };
        let proto_len = serialize_due_batch_proto(batch.clone()).len();
        let json_len = serde_json::to_vec(&batch).unwrap().len();
        assert!(proto_len < json_len, "proto {proto_len} not smaller than json {json_len}");
    }

    #[test]
    fn test_due_batch_proto_rejects_garbage() {
        // Invalid protobuf wire bytes surface a SerializationError rather than panicking.
        let result = deserialize_due_batch_proto(vec![0xff, 0xff, 0xff, 0xff]);
        assert!(matches!(result, Err(CoreError::SerializationError { .. })));
    }

    #[test]
    fn test_review_session_state_machine() {
        // answer_card buffers into DB_CONN when a DB is configured; serialize with the buffer tests.
        let _guard = db_test_guard();
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
    fn test_format_queue_eta() {
        // Empty queue.
        assert_eq!(format_queue_eta(0, None), "All caught up 🎉");
        assert_eq!(format_queue_eta(-3, Some(5000)), "All caught up 🎉");

        // No observed pace: flat 10s/card fallback.
        assert_eq!(format_queue_eta(3, None), "~30s to finish");
        assert_eq!(format_queue_eta(6, None), "~1 min to finish");
        // A zero/negative average is ignored in favour of the fallback.
        assert_eq!(format_queue_eta(3, Some(0)), "~30s to finish");

        // Real pace: 20 cards at 8s each = 160s -> rounds up to 3 min.
        assert_eq!(format_queue_eta(20, Some(8000)), "~3 min to finish");
        // 4 cards at 12.5s each = 50s.
        assert_eq!(format_queue_eta(4, Some(12500)), "~50s to finish");
        // 59s stays in seconds; 60s rolls over to a minute; 61s rounds up to two.
        assert_eq!(format_queue_eta(59, Some(1000)), "~59s to finish");
        assert_eq!(format_queue_eta(60, Some(1000)), "~1 min to finish");
        assert_eq!(format_queue_eta(61, Some(1000)), "~2 min to finish");
    }

    #[test]
    fn test_average_answer_time_ms() {
        let _guard = db_test_guard();
        let temp_dir = std::env::temp_dir();
        let db_path = temp_dir.join("test_wearos_avg.db").to_string_lossy().to_string();
        let _ = std::fs::remove_file(&db_path);
        init_database(db_path.clone()).unwrap();

        // Empty buffer averages to None (queue-less days shouldn't skew the estimate).
        assert_eq!(average_answer_time_ms().unwrap(), None);

        for (i, ms) in [4000_i64, 6000, 8000].iter().enumerate() {
            save_answer(WearosAnswer {
                id: format!("avg_{i}"),
                note_id: 100 + i as i64,
                ord: 0,
                ease: 3,
                time_taken_ms: *ms,
            })
            .unwrap();
        }
        assert_eq!(average_answer_time_ms().unwrap(), Some(6000));

        let _ = std::fs::remove_file(&db_path);
    }

    #[test]
    fn test_buffered_answer_stats() {
        let _guard = db_test_guard();
        let temp_dir = std::env::temp_dir();
        let db_path = temp_dir.join("test_wearos_stats.db").to_string_lossy().to_string();
        let _ = std::fs::remove_file(&db_path);
        init_database(db_path.clone()).unwrap();

        // Empty buffer: zero count, zero age.
        let empty = buffered_answer_stats().unwrap();
        assert_eq!(empty.count, 0);
        assert_eq!(empty.oldest_age_secs, 0);

        save_answer(WearosAnswer {
            id: "s1".to_string(),
            note_id: 1,
            ord: 0,
            ease: 3,
            time_taken_ms: 1000,
        })
        .unwrap();
        save_answer(WearosAnswer {
            id: "s2".to_string(),
            note_id: 2,
            ord: 0,
            ease: 2,
            time_taken_ms: 2000,
        })
        .unwrap();
        let stats = buffered_answer_stats().unwrap();
        assert_eq!(stats.count, 2);
        assert!(stats.oldest_age_secs >= 0, "age should be non-negative");

        let _ = std::fs::remove_file(&db_path);
    }

    #[test]
    fn test_buffer_error_paths_without_init() {
        let _guard = db_test_guard();
        // Point the shared connection at nothing, mimicking a process that never called
        // init_database. Every buffer op must surface a DatabaseError rather than panic.
        *DB_CONN.lock().unwrap() = None;
        assert!(matches!(save_answer(sample_answer()), Err(CoreError::DatabaseError { .. })));
        assert!(matches!(get_buffered_answers(), Err(CoreError::DatabaseError { .. })));
        assert!(matches!(average_answer_time_ms(), Err(CoreError::DatabaseError { .. })));
        assert!(matches!(buffered_answer_stats(), Err(CoreError::DatabaseError { .. })));
        assert!(matches!(
            remove_buffered_answers(vec!["x".to_string()]),
            Err(CoreError::DatabaseError { .. })
        ));
    }

    fn sample_answer() -> WearosAnswer {
        WearosAnswer { id: "s".to_string(), note_id: 1, ord: 0, ease: 3, time_taken_ms: 1000 }
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
    fn test_deck_children_deep_tree() {
        // Four levels deep: descending resolves the right own_deck and single child at each level.
        let decks = vec![
            DeckInfo { id: 1, full_name: "A".to_string() },
            DeckInfo { id: 2, full_name: "A::B".to_string() },
            DeckInfo { id: 3, full_name: "A::B::C".to_string() },
            DeckInfo { id: 4, full_name: "A::B::C::D".to_string() },
        ];
        let level = deck_children(decks.clone(), "A::B".to_string());
        assert_eq!(level.own_deck.as_ref().unwrap().id, 2);
        assert_eq!(level.children.len(), 1);
        let c = &level.children[0];
        assert_eq!(c.name, "C");
        assert!(c.is_folder, "C has D below it");
        assert_eq!(c.path, "A::B::C");

        // The deepest node is a leaf.
        let deepest = deck_children(decks, "A::B::C".to_string());
        assert!(!deepest.children[0].is_folder);
        assert_eq!(deepest.children[0].id, 4);
    }

    #[test]
    fn test_deck_children_folder_without_own_deck() {
        // "A" is only ever a prefix (no flat deck named exactly "A"), so it's a folder with id -1.
        let decks = vec![
            DeckInfo { id: 1, full_name: "A::B".to_string() },
            DeckInfo { id: 2, full_name: "A::C".to_string() },
        ];
        let root = deck_children(decks.clone(), String::new());
        let a = &root.children[0];
        assert_eq!(a.name, "A");
        assert!(a.is_folder);
        assert_eq!(a.id, -1, "no studyable deck sits exactly at 'A'");

        // Descending into A shows no own_deck, just the two leaves.
        let level = deck_children(decks, "A".to_string());
        assert!(level.own_deck.is_none());
        let names: Vec<_> = level.children.iter().map(|c| c.name.as_str()).collect();
        assert_eq!(names, vec!["B", "C"]);
    }

    #[test]
    fn test_deck_children_duplicate_leaf_names() {
        // The same leaf name under two different folders must stay distinct by path.
        let decks = vec![
            DeckInfo { id: 1, full_name: "A::Grammar".to_string() },
            DeckInfo { id: 2, full_name: "B::Grammar".to_string() },
        ];
        let a = deck_children(decks.clone(), "A".to_string());
        assert_eq!(a.children[0].path, "A::Grammar");
        assert_eq!(a.children[0].id, 1);
        let b = deck_children(decks, "B".to_string());
        assert_eq!(b.children[0].path, "B::Grammar");
        assert_eq!(b.children[0].id, 2);
    }

    #[test]
    fn test_answer_html_no_false_marker() {
        // A literal "id=answer" appearing inside text (not a tag) still splits at the next '>',
        // documenting the current behaviour so a future change is a conscious one.
        assert_eq!(answer_html("front<hr id=answer>".to_string()), "");
        // Multiple markers: split at the first.
        assert_eq!(
            answer_html("Q<hr id=answer>A1<hr id=answer>A2".to_string()),
            "A1<hr id=answer>A2"
        );
    }

    #[test]
    fn test_local_sqlite_buffering() {
        let _guard = db_test_guard();
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
