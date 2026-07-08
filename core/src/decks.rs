//! Deck folder hierarchy (Anki `::` names) and review-time parsing — pure logic driving the watch UI.

use crate::models::{DeckInfo, DeckLevel, DeckNodeInfo};

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
    children.sort_by_key(|c| !c.is_folder);

    DeckLevel { own_deck, children }
}

/// Parse AnkiDroid's NEXT_REVIEW_TIMES.
///
/// It's a JSON-array string whose numbers are wrapped in Unicode bidi isolates (e.g.
/// `["\u{2068}10\u{2069}m", ...]`). Returns clean values like "10m", falling back to a lenient
/// bracket split when the input isn't valid JSON.
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
