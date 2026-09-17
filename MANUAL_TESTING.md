# Manual pre-release test checklist

Everything in this file needs a real paired phone + Wear OS device (or a phone AVD + Wear AVD
pair); it cannot be covered headlessly. CI covers the Rust core, the JVM/Robolectric suites,
Android lint, R8, and 16 KB `.so` alignment — but nothing below. Walk this list before cutting
a release; tick items only after seeing them on the target device.

## Setup

- Real AnkiDroid install on the phone with a collection containing: a large deck (500+ cards),
  a deck with images and pitch-accent CSS (rich-rendering path), a 3-button deck, a 4-button
  deck. Both **Force Mock Mode** and **Read-only mode** OFF in the companion settings.
- Watch paired via the Wear OS companion flow (not adb-only install) so the GMS Data Layer
  is live. GMS on fresh emulator images takes a few minutes to settle after cold boot.

## Sync (Data Layer)

- [ ] Manual "Sync now": deck list arrives on the watch, checkbox filters respected.
- [ ] Watch-requested refresh (`/request_decks`) returns a filtered deck list over Bluetooth.
- [ ] Large due batch survives an off-body/off-Bluetooth interruption; watch shows cached decks
      and reviews work fully offline (answers buffer locally).
- [ ] Reconnect: buffered answers drain to the phone, `/clear_answers` arrives, the buffer
      empties, and nothing is applied twice (watch "Buffered: N" indicator drops to 0).
- [ ] Read-only mode ON: watch reviews do NOT change any card's due date/interval.

## Write-back (against real AnkiDroid)

- [ ] Grade a learning card on the watch; in AnkiDroid the card advances (queue/due/reps
      change, a revlog row is written). Force-stop AnkiDroid first to defeat its in-process
      cache when re-checking counts.
- [ ] Multi-deck session: answers for every deck land, not just the current deck.
- [ ] Known constraint (issue #20): grade the relearned card a second time within one session
      and confirm whether it lands; record the result on the issue.
- [ ] Undo on the watch then sync: the undone grade is not resurrected by a stale buffer.

## Watch UI (round display)

- [ ] All screens reachable and legible on a round watch (Pixel Watch + Galaxy Watch if
      possible): dashboard, deck list, review front/back, grade grid (3 and 4 button), summary,
      settings. Nothing clipped by the bezel; grade buttons inside the safe area.
- [ ] Rotary crown/bezel scrolls on every screen, including Settings and the grade selection.
- [ ] Predictive back gesture animates (not a hard cut) on both apps.
- [ ] Tile renders, refreshes after reviews, and taps through to the review screen.
- [ ] TTS voice reviews read front on load and answer on reveal (opt-in toggle).
- [ ] Keep-screen-on toggle actually holds the screen; verify it does not leak after leaving
      the review screen.

## Phone side

- [ ] Auto-sync (WorkManager) fires at the configured interval with the app backgrounded
      (check `dumpsys jobscheduler` if in doubt).
- [ ] Deck search, Select all/none, per-deck due counts render correctly.
- [ ] Companion behaves sanely with AnkiDroid missing: debug build shows mock cards;
      release build shows the empty state and no crash.

## Battery & performance

- [ ] `adb bugreport` + Battery Historian around one review + sync session on the watch; no
      unexpected wakelocks; buffer DB queries not on binder/main threads (check for ANRs in
      `logcat` while the Tile refreshes).

## Compatibility

- [ ] Staged rollout check: pre-upgrade phone + post-upgrade watch (and vice versa) still
      sync (mixed-version tolerance of the payload format).