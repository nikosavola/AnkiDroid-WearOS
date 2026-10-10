//! Deck hierarchy parsing and review-time parsing.

use crate::*;

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
fn test_parse_review_times() {
    // Valid JSON array with bidi isolates around the numbers (real AnkiDroid shape).
    let raw = "[\"\u{2068}10\u{2069}m\", \"\u{2068}2\u{2069}d\", \"\u{2068}4\u{2069}d\"]";
    assert_eq!(parse_review_times(raw.to_string()), vec!["10m", "2d", "4d"]);

    // Lenient fallback for a non-JSON comma list.
    assert_eq!(parse_review_times("1m, 10m, 4d, 7d".to_string()), vec!["1m", "10m", "4d", "7d"]);

    assert_eq!(parse_review_times(String::new()), Vec::<String>::new());
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
