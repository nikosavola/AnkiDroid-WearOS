//! Dev-only helper: emit a serialized due-batch (protobuf+zstd) for a mock/real deck, so the wear
//! app's full offline review flow can be driven on an emulator or a real watch without the phone's
//! fetch/push path. Not part of the shipped surface.
//!
//! Usage:
//!   cargo run --example mock_deck -- <out_file> [<deck_id> <deck_name> <card_id>:<note_id>:<front>:<back> ...]
//! With only <out_file>, emits a default 2-card mock deck. Otherwise builds a deck from the given
//! id/name and one card per `card_id:note_id:front:back` triple (ord 0, 4 buttons). Passing the
//! REAL AnkiDroid card_id/note_id/ord makes the graded card match AnkiDroid's primed front card, so
//! write-back actually applies it.
#![allow(clippy::print_stderr, reason = "dev tool, not shipped code")]
use std::io::Write;
use wearos_core::{WearosCard, WearosDueBatch, serialize_due_batch};

fn main() {
    let mut args = std::env::args().skip(1);
    let out = args.next().expect("usage: mock_deck <out_file> [<deck_id> <deck_name> <spec>...]");

    let rest: Vec<String> = args.collect();
    let batch = if rest.is_empty() {
        let card = |id: i64, front: &str, back: &str| WearosCard {
            id,
            note_id: id,
            ord: 0,
            front: front.to_string(),
            back: back.to_string(),
            button_count: 4,
            next_review_times: vec!["1m".into(), "6m".into(), "10m".into(), "4d".into()],
        };
        WearosDueBatch {
            deck_id: 9001,
            deck_name: "Emulator Smoke".to_string(),
            cards: vec![card(101, "犬 (いぬ)", "dog"), card(102, "猫 (ねこ)", "cat")],
        }
    } else {
        let deck_id: i64 = rest[0].parse().expect("deck_id must be an integer");
        let deck_name = rest[1].clone();
        let cards = rest[2..]
            .iter()
            .map(|spec| {
                let p: Vec<&str> = spec.splitn(4, ':').collect();
                WearosCard {
                    id: p[0].parse().expect("card_id"),
                    note_id: p[1].parse().expect("note_id"),
                    ord: 0,
                    front: p[2].to_string(),
                    back: p[3].to_string(),
                    button_count: 4,
                    next_review_times: vec!["1m".into(), "6m".into(), "10m".into(), "4d".into()],
                }
            })
            .collect();
        WearosDueBatch { deck_id, deck_name, cards }
    };

    let bytes = serialize_due_batch(batch).expect("serialize");
    std::fs::File::create(&out).unwrap().write_all(&bytes).unwrap();
    eprintln!("wrote {} bytes to {out}", bytes.len());
}
