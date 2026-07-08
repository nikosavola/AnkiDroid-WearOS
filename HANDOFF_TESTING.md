# Hand-off: Testing & Debugging Guide

This document is written for the next contributor (human or LLM) who has the **full Android
toolchain** (SDK + NDK r28+, JDK 21, a real phone + Wear OS device or emulators) and a real
AnkiDroid install. It records:

1. **What was changed** in the recent sessions and exactly how to verify each change.
2. **What remains** from the backlog, with implementation notes and how to test.

## Environment the changes were authored under (important caveat)

The changes below were made in an environment that had **only the Rust toolchain (cargo) and
JDK 21 — no Android SDK/NDK and no device/emulator**. Consequences:

- Everything in `core/` (Rust) is **fully compiled + tested** here: `cargo test`,
  `cargo clippy --all-targets -- -D warnings`, and `cargo fmt --check` all pass.
- Every **Kotlin/Android** change is **unbuilt and unverified**. It was written to match existing
  patterns and the generated UniFFI binding names, but it has **not** been through
  `./gradlew compileDebugKotlin`, lint, detekt, ktfmt/ktlint, or a device. Treat all Kotlin edits
  as "plausible but must be compiled and run."

### First thing to run

```bash
# 1. Rust core (should already be green)
cargo test --manifest-path core/Cargo.toml
cargo clippy --manifest-path core/Cargo.toml --all-targets -- -D warnings
cargo fmt --manifest-path core/Cargo.toml --all -- --check

# 2. Generate bindings + compile everything (this is what could NOT be run here)
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew generateBindings
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew compileDebugKotlin
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew lintAll
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew testDebugUnitTest
```

If Kotlin fails to compile, the most likely culprit is the Wear M3 `ColorScheme` constructor in
`wear/.../AnkiWearTheme.kt` (see item **Watch light mode** below) — it is the single riskiest edit.

---

# Part 1 — Changes made (verify these)

## 1.1 Tile ETA from the real average answer time  ✅ Rust-verified, Kotlin unverified

- **Rust** (`core/src/tile.rs`, `core/src/db.rs`): new `format_queue_eta(card_count, avg_ms)` and
  `average_answer_time_ms()`. Unit-tested in `core/src/lib.rs`
  (`test_format_queue_eta`, `test_average_answer_time_ms`).
- **Kotlin** (`wear/.../AnkiTileService.kt`): the Tile now initializes the shared buffer DB and reads
  the mean answer time, falling back to the flat 10 s/card estimate when the buffer is empty.
- **How to test on device:**
  - Fresh install → Tile should read `~<count*10>s`/`~N min` (no history yet).
  - Do several reviews on the watch, then **before** syncing them back, refresh the Tile → ETA
    should reflect your real pace (buffered answers still present).
  - After syncing answers back (buffer drained), ETA falls back to the flat estimate — expected.
  - Verify the Tile does not ANR: `average_answer_time_ms()` runs a trivial `AVG()` query on the
    Tile's binder thread.

## 1.2 gzip folded into the sync (de)serializers  ✅ Rust-verified, Kotlin unverified

- **Rust** (`core/src/sync.rs`): `serialize_due_batch` now gzips the JSON; `deserialize_due_batch`
  sniffs the gzip magic (`1f 8b`) and inflates, otherwise parses raw JSON (backward compatible).
  New `flate2` dependency (pure-Rust `miniz_oxide` backend, so `unsafe_code = forbid` still holds).
  Tests: `test_due_batch_is_gzipped_and_smaller`, `test_deserialize_due_batch_accepts_raw_json`,
  `test_deserialize_due_batch_rejects_garbage`, and the existing round-trip test.
- **Kotlin**: the phone no longer calls `gzip(...)` before putting the Asset
  (`phone/.../SyncService.kt`, `phone/.../WearSyncService.kt`), and the watch no longer calls
  `gunzip(...)` before `deserializeDueBatch` (`wear/.../WearSyncService.kt`). The now-dead
  `Compression.kt` (both apps) and `wear/.../CompressionTest.kt` were **deleted** — the compression
  contract lives in Rust now.
- **Answers path is intentionally NOT compressed** (a buffered-answer list is tiny). If you want
  symmetry later, fold gzip into `serialize_answers`/`deserialize_answers` the same way and drop any
  Kotlin compression on the `/answers` path (there is none today).
- **How to test on device:** sync a large/dense deck (Kanji/Kaishi with lots of HTML) phone→watch.
  It must arrive and open. Because `deserialize_due_batch` auto-detects, a watch running the new
  code can still read a batch left by an *old* phone (raw JSON) and vice-versa — worth checking the
  mixed-version case if you care about staged rollout. Confirm `DeckCache` files on the watch are
  now gzipped (smaller) and still open.

## 1.3 ReviewSession explicit native free (FFI/GC hygiene)  ⚠️ Kotlin unverified

- `ReviewSession` is a `#[uniffi::Object]` holding a Rust handle the JVM GC can't size.
  `wear/.../MainActivity.kt` now calls `reviewSession?.destroy()` via a `clearSession()` helper
  whenever the session is dropped (back out of a session, "Change Deck", finished→sync, `onDestroy`)
  and before loading a replacement session.
- **VERIFY the generated method name.** UniFFI 0.28 generates `destroy()` on objects (and they
  implement `AutoCloseable`). If the generated API differs, adjust (`close()` / `use { }`).
- **How to test:** open deck → review → back → open another deck, repeatedly. No crash, no
  use-after-free (would manifest as a native panic/segfault the moment a destroyed session is
  touched). Confirm the review screen never touches `reviewSession` after `clearSession()` (the
  `BackHandler` sets state so the composable swaps to the picker in the same frame — watch for a
  one-frame race where a recomposition reads a just-destroyed session; if seen, null the session
  state *before* calling destroy, or post destroy to the next frame).

## 1.4 Watch Settings screen: theme, text size, card font  ⚠️ Kotlin unverified, HIGH RISK spot

New files: `wear/.../WatchSettings.kt` (SharedPreferences store) and `wear/.../AnkiWearTheme.kt`
(theme wrapper). `wear/.../MainActivity.kt` gained settings state, a "Settings" button on the deck
picker, and a `SettingsScreen` composable. `HtmlText` now uses the theme's `onSurface` color (so it
is legible in light mode) and applies the chosen font scale + typeface.

Backlog items this covers: *watch settings menu*, *watch light mode + follow system theme*,
*watch font choice / larger CJK faces*, and *rich formatting/size support (font sizing)*.

- **HIGH RISK:** `AnkiWearTheme.kt` builds a light `androidx.wear.compose.material3.ColorScheme`
  with named color roles (`background`, `onBackground`, `surfaceContainerLow`, `surfaceContainer`,
  `surfaceContainerHigh`, `onSurface`, `onSurfaceVariant`). If those role names don't match the
  `wear-compose = 1.6.2` catalog, **the whole `:wear` module won't compile**. Fix by aligning the
  names to the actual `ColorScheme` constructor (check the AAR / docs), or, as a fallback, use the
  library's `MaterialTheme.colorScheme` default for both and only override colors you're sure of.
- **How to test:**
  - Settings → Theme = Light: the whole UI (picker, deck screen, review card front/back text)
    switches to light and card text stays dark-on-light (not white-on-white). Dark restores.
    Auto (SYSTEM) follows the OS theme *if the device reports one* — many round watches always
    report dark, so Auto may look like Dark on them (documented, not a bug).
  - Text size S/M/L/XL changes the live preview and the actual card text in review.
  - Card font Sans/Serif/Mono changes the preview and the review card typeface. Check a CJK card:
    Serif/larger sizes should be easier to read.
  - Confirm settings persist across app restarts (they're in SharedPreferences).
  - Round-display check: the three/four choice chips per row must fit inside the bezel.

## 1.5 Phone read-only / safe test mode  ⚠️ Kotlin unverified

Backlog item: *"Consider a safe/read-only test mode so verifying sync doesn't write real reviews."*

- `phone/.../AnkiDroidBridge.writeAnswersBack(answers, readOnly)` short-circuits when read-only:
  it does **not** call `ContentResolver.update`, but returns all ids as "settled" so the
  `clear_answers` handshake still fires and the watch buffer clears. **Tradeoff:** those reviews are
  discarded, not applied. This is for verifying the *plumbing* without mutating the collection.
- Toggle is in the phone Settings dialog (`MainActivity.showSettingsDialog`) and shown in the
  Connection-info dialog. `phone/.../WearSyncService` reads it and passes it through.
- **How to test:** enable read-only, review + sync from the watch. AnkiDroid scheduling must be
  **unchanged** (check a card's due date/interval before and after). The watch's outstanding count
  should still drop to 0 (buffer cleared). Disable it and confirm reviews DO land again.

## 1.6 Rust test-coverage growth  ✅ Rust-verified

`core/src/lib.rs` gained edge-case tests for `clean_html`/`html_for_display` (unterminated/nested
tags, multiple `[sound:]`, case-insensitive `<STYLE>`), `deck_children` (deep trees, folders with no
own deck, duplicate leaf names), buffer error paths when the DB was never initialized, and
`answer_html` marker edge cases. Buffer tests are serialized with a lock (shared global `DB_CONN`).

## 1.7 UniFFI async/suspend — design note (seed code removed in §1.19)

The migration target: expose heavy work as Rust `async fn`, which UniFFI maps to Kotlin
`suspend fun`, so the Kotlin side drops `withContext(Dispatchers.IO) { blockingFfi() }`. An earlier
`async_ops.rs` scaffolded this with `deserialize_due_batch_async` etc., but those bodies had no
`.await` (they only earned a lint suppression), so they were **removed** in the lint-cleanup pass
(§1.19). To actually implement: add the `tokio` feature to the `uniffi` dep and annotate the
real work with `#[uniffi::export(async_runtime = "tokio")]` + `tokio::task::spawn_blocking`.
- **Important caveat (in the module doc too):** these bodies have no `.await`, so they don't yet
  move CPU work off the caller's thread — they prove the binding shape. To *actually* offload,
  add the `tokio` feature to the `uniffi` dep and use `#[uniffi::export(async_runtime = "tokio")]`
  + `tokio::task::spawn_blocking`. Kept runtime-free so the crate still builds with no runtime.
- **How to test:** after `generateBindings`, call `deserializeDueBatchAsync(bytes)` from a coroutine
  in the watch's `loadDeckSession` instead of `withContext(Dispatchers.IO){ deserializeDueBatch }`;
  confirm it suspends and returns correctly, and that cancellation behaves. A host-JVM
  `runBlocking { }` test is possible but async-over-JNA under Robolectric wasn't validated here.

## 1.8 Protobuf wire format (experimental, parallel to JSON)  ✅ Rust-verified

`core/src/proto.rs` defines the due batch as `prost`-annotated structs (no `protoc`, no `build.rs`
— derive macros only) and exports `serialize_due_batch_proto` / `deserialize_due_batch_proto`.
Tests: round-trip, "smaller than raw JSON", and garbage-rejection. **Not wired into the Kotlin sync
path** — it's a measurable A/B candidate. To adopt: swap `serde_json` for these inside
`serialize_due_batch`/`deserialize_due_batch` (keep the gzip wrapper), regenerate, and confirm both
apps still round-trip. Combined with gzip it should beat gzipped-JSON on dense decks — measure.

## 1.9 Predictive back  ⚠️ manifest-only, verify on device

Added `android:enableOnBackInvokedCallback="true"` to both `<application>` tags. The watch already
uses Compose `BackHandler` and the phone uses the framework back; with the flag on, the OS can run
ahead-of-time back animations. **How to test:** on Android 13+/Wear, the predictive-back gesture
shows the peek/animation; all existing back behaviors (leave review, up-a-folder, close settings)
still fire. For custom animated transitions, migrate specific `BackHandler`s to `PredictiveBackHandler`.

## 1.10 Phone deck-tree motion (stopgap)  ⚠️ unverified

`phone/.../MainActivity` sets `deckListLayout.layoutTransition = LayoutTransition()` so rows animate
in/out on expand/collapse instead of snapping. This is a stopgap — a true M3 Expressive tree
(spring chevron rotation, per-subtree animation, tonal emphasis) still wants the Compose migration
(see 2.6). **How to test:** expand/collapse a folder; rows should animate rather than jump. If the
full-list rebuild makes the whole list crossfade (janky), that confirms the rebuild architecture is
the real blocker → do 2.6.

## 1.11 New unit tests

- Rust: proto round-trip / size / garbage (`test_due_batch_proto_*`).
- Kotlin (phone): `SyncServiceTest` covers the extracted pure `SyncService.syncProgressPercent`
  (ramp, ≤95 clamp, zero-cap guard) — no device/native needed.
- Kotlin (wear): `CoreFfiTest.serializeDueBatchProto_roundTrips` exercises the new proto bindings.

## 1.12 Phone header restyle (Material 3)  ⚠️ unverified

The phone top bar was a single `HeadlineSmall` "AnkiDroid WearOS Companion" line. It's now a
Material 3 large-top-app-bar-style header: a primary-colored, wide-tracked caps overline
("ANKIDROID") above a `HeadlineMedium` "WearOS Companion" title (`colorOnSurface`), with the gear
converted from a raw `ImageButton` to a Material `materialIconButtonStyle` button (matching the deck
expanders). **How to test:** launch the phone app in light and dark; the overline should be the
theme primary color, the title should be a large on-surface headline, and the gear should be a
properly-sized (48dp) tinted icon button top-aligned in the corner. Confirm the title doesn't clip
on a narrow device (it's `maxLines = 2`, ellipsized).

## 1.13 Automatic background deck sync (WorkManager)  ⚠️ unverified, new dependency

Adds recurring background sync so the watch gets fresh due cards without the user pressing "Sync".
- **New dep:** `androidx.work:work-runtime-ktx:2.9.1` (catalog `androidx-work-runtime`, added to
  `phone/build.gradle.kts`). WorkManager self-initializes via its `androidx.startup` provider — no
  Application class or manifest entry needed, and it re-schedules periodic work across reboots (so
  no BootReceiver).
- **Refactor:** the push logic moved out of `SyncService.runSync()` into a shared `DeckSync.run()`
  (`DeckSync.kt`), used by both the foreground `SyncService` (user "Sync now", with notification)
  and the new background worker. `SyncService` is now a thin progress wrapper. `MAX_SCAN_DECKS` and
  the batch/deck constants live in `DeckSync`; `syncProgressPercent` stays on `SyncService`
  (referenced by both, still unit-tested).
- **New files:** `AutoSyncWorker` (CoroutineWorker running `DeckSync.run`) and `AutoSync` (scheduling
  — unique periodic work, **user-configurable interval** (default 6h, 1–24h), `requiresBatteryNotLow`,
  linear backoff). Launch-time re-apply uses `KEEP` (doesn't reset the timer); a Settings change uses
  `UPDATE` so a new interval takes effect immediately on the same unique job.
- **Safety:** `DeckSync.run` returns `null` (no-op) when AnkiDroid isn't reachable and mock mode
  isn't forced, so background runs never silently push mock decks. `DataClient.putDataItem` is
  offline-first, so a sync while the watch is disconnected still queues and delivers later.
- **UI:** a "Auto-sync decks in background" switch (default ON) plus an "Auto-sync every: Nh"
  interval slider (1–24h, disabled when the switch is off) in the phone Settings dialog; Done calls
  `AutoSync.apply(enabled, hours)`. `MainActivity.onCreate` calls `AutoSync.applyFromSettings`. The
  debug dialog shows "every Nh" / "off".
- **How to test:**
  - Toggle the switch → `adb shell dumpsys jobscheduler | grep auto_deck_sync` (or WorkManager's
    `getWorkInfosForUniqueWork`) shows the job enqueued/cancelled.
  - Force a run without waiting 6h:
    `adb shell cmd jobscheduler run -f com.ichi2.anki <jobId>`, or temporarily drop the interval,
    or trigger via a one-time `OneTimeWorkRequest` in a debug build. Confirm the watch receives the
    decks/batches.
  - With AnkiDroid uninstalled/permission revoked and mock off, the worker must be a no-op (check
    Logcat "nothing to sync"), NOT push mock decks.
  - Verify battery-not-low constraint by running under battery saver.
  - **Unverified risk:** WorkManager version vs AGP 8.13.2/compileSdk 35 (should be fine; bump to
    2.10.x if needed), and that offscreen `WebView` bitmap rendering works from the worker context
    (it goes through `render()`'s `Dispatchers.Main` switch, same as the foreground path).

## 1.14 Card-text, buffer-stats, and review UX polish

- **`clean_html` HTML-entity decoding + block breaks** (`core/src/html.rs`, Rust-verified). The
  TTS/plain path now decodes named *and* numeric entities (`&#39;`, `&#x2F;`, `&quot;`, `&mdash;`,
  …) in a single correct pass (so `&amp;lt;` → `&lt;`, not `<`), and breaks lines on closing block
  tags (`</div>`, `</li>`, `</h1..6>`, `</tr>`, `</blockquote>`) since Anki wraps content in `<div>`s.
  Before this, TTS read "&#39;" aloud and multi-`div` cards ran together. Tests:
  `test_clean_html_decodes_entities`, `test_clean_html_breaks_block_elements`.
- **Buffer stats FFI** (`buffered_answer_stats() -> BufferStats { count, oldest_age_secs }`,
  Rust-verified, `test_buffered_answer_stats`). The watch's `updateOutstandingCount` now reads this
  instead of counting the full answer list, and shows a "⚠ Oldest unsynced Nd ago" nudge on the deck
  screen when reviews have waited ≥ 1 day. **Test:** review offline, wait/deep-sleep, confirm the
  nudge appears with the right age; confirm it clears after a successful sync.
- **Haptic feedback on grading** (watch, unverified). `ReviewCardScreen` fires a short
  `HapticFeedbackType.LongPress` tick when a grade registers (glanceable/eyes-free confirmation).
  **Test:** grade a card; feel one tick; ensure no double-fire when tapping fast (guarded by
  `isAnswering`).

## 1.15 Undo, phone deck search/bulk-select, last-synced, keep-screen-on, per-deck counts

Batch of the "other improvements" list.

- **Undo last grade** (Rust-verified + watch UI). `ReviewSession` gained an answer-id stack:
  `can_undo()` and `undo_last_answer()` step the queue back one card, pop + delete that answer from
  the local buffer (so it never reaches the phone), and re-show the card un-revealed —
  multi-level. Test: `test_review_session_undo`. Watch: a "↩" `CompactButton` (top-start of the
  front view, shown when `canUndo()`) calls `undoLastAnswer`. **Verify** the button placement on a
  round display (top corners clip) and that undo after an auto-synced grade still removes it locally
  (it can't un-write one already applied on the phone — that's expected).
- **Phone deck search** — a Material `TextInputLayout` search box filters the tree by deck name
  (case-insensitive); folders force-open while filtering; an empty result shows a "No decks match"
  hint. **Test** with the 164-deck collection.
- **Phone bulk select** — "Select all" / "Select none" outlined buttons flip every `deck_<id>` pref.
- **Last synced caption** — `DeckSync` records `KEY_LAST_SYNC_MS` on every successful run (foreground
  or background); the phone shows "Last synced 5 minutes ago" (via `DateUtils`) under the header,
  refreshed on sync-done and `onResume`. **Test** that a background auto-sync updates it too.
- **Keep screen on during review** (watch, opt-in). `WatchSettings.keepScreenOn` (default off) +
  a Settings switch; `ReviewCardScreen` holds `FLAG_KEEP_SCREEN_ON` via a `DisposableEffect` only
  while reviewing. **Test** the flag clears when leaving the review (check it doesn't leak on).
- **Per-deck due counts** (phone, best-effort, LOW confidence). `AnkiDroidBridge.fetchDeckCounts()`
  reads the Deck provider's `deck_counts` column *if it exists*, summing its integers; rows show
  " · N due" (folders sum their subtree). **Absence is treated as unknown → no annotation, never
  0.** **VERIFY:** the column name/format against the installed AnkiDroid (this was written without
  a device); if counts look wrong or missing, confirm the real column/semantics in
  `FlashCardsContract.Deck`. Loaded off the main thread after the tree draws (the provider may
  compute counts collection-wide, which can be slow).

## 1.16 Compose Tiles + phone Compose migration (partial)

Genuine partial progress on the two big documented items.

- **Interactive + auto-refreshing Tile** (`wear/.../AnkiTileService.kt`, unbuilt). The tile is now
  tappable — a `ModifiersBuilders.Clickable` + `ActionBuilders.LaunchAction` opens `MainActivity` —
  and sets `setFreshnessIntervalMillis(30 min)` so the due count/ETA refresh on their own. This uses
  the protolayout APIs already on the classpath (low risk). **Test:** tap the tile → app opens;
  leave it on the carousel and confirm it updates after review/sync. The *full* Compose-for-Tiles /
  Glance rebuild (reading a StateFlow from the core) is still pending — this modernizes the existing
  protolayout tile rather than replacing the builder stack.
- **Phone Compose migration — phase 1** (`phone/.../DeckTreeCompose.kt` + build setup). Adds Jetpack
  Compose Material 3 to the `:phone` module (compose compiler plugin, `buildFeatures.compose`,
  material3/foundation/ui/animation deps) and a real **M3 Expressive animated deck tree**:
  spring-rotating chevron (`animateFloatAsState` + `spring`), animated expand/collapse
  (`AnimatedVisibility` height+fade), tonal folder surfaces. It reuses the pure
  `buildDeckTree`/`collectDeckIds` helpers and hoists checked-state via callbacks, so it's drop-in.
  **NOT yet hosted** — `MainActivity` still runs the Views tree (with the `LayoutTransition`
  stopgap). To finish: host `DeckSelectionTree` in a `ComposeView`/`setContent` and retire
  `renderDeckTree`/`addDeckNodeViews`.
  - **HIGH RISK / top thing to verify:** the phone Compose dependency versions. I pinned
    `androidx.compose.material3:material3` to **1.3.2** and rode foundation/ui/animation on the
    repo's `compose = 1.9.0`. If Gradle can't resolve that combination against Kotlin 2.4.0's compose
    compiler, adopt a **Compose BOM** (`platform("androidx.compose:compose-bom:<ver>")`) and drop the
    explicit versions — that's the version-safe fix. This is the single most likely thing to break
    `:phone` compilation on a real toolchain.
  - A `@Preview` (`DeckSelectionTreePreview`) is included so the tree can be rendered in the IDE /
    screenshot-tested without wiring it into the app.

## 1.17 Session summary, reviews-today streak, phone selected-count

- **End-of-session summary** (Rust-verified + watch UI). `ReviewSession.get_summary()` returns
  `SessionSummary { total, again, hard, good, easy, total_time_ms }` (the undo stack now holds full
  answers so it also backs the tally and stays correct after undo). The watch Finished screen shows
  "N cards · Mm Ss" + the ease split. Test: `test_session_summary`.
- **"Reviewed today" counter** (Rust-verified + watch UI). A new append-only `review_log` table
  (written on every `save_answer`, independent of the buffer so it survives sync-clearing) backs
  `reviews_since(since_secs)`. The watch passes local midnight (`todayMidnightSecs`) and shows
  "Reviewed today: N 🔥" on the deck + finished screens. Test: `test_reviews_since`.
  - **Known caveat:** the log is append-only, so an *undo* does not decrement it — "reviewed today"
    counts grading attempts, which slightly over-counts if you undo. Acceptable for a motivational
    number; documented so it isn't mistaken for a bug.
- **Phone "Decks to sync (N/M)"** — the header now shows selected vs total, updated on every toggle
  (folder re-render and single-deck check) without rebuilding the tree.

## 1.18 Language-modernization pass

- **Rust** (verified — 35 tests, clippy `-D warnings`, fmt): new `core/src/util.rs` with panic-free
  `unix_secs()` / `unix_millis()` helpers (via `Duration::map_or`) replacing the repeated
  `SystemTime::now().duration_since(UNIX_EPOCH).unwrap()...` dance in `db.rs`/`session.rs`;
  `get_db_connection` uses a `let-else` and clones the path out of the mutex guard so the lock
  releases before the blocking `Connection::open`; inline `#[allow(...)]`s became
  `#[expect(..., reason = "...")]` (the modern lint-expectation idiom — fails if the lint stops
  firing). Edition is already 2024 and let-chains were already in use (`answer_html`, undo).
- **Kotlin** (unbuilt): a `DeckNode.deckIds()` `buildList` extension replaces the
  `mutableListOf().also { collectDeckIds(...) }` pattern at every call site (main + Compose + test);
  the watch's `todayMidnightSecs` moved from `java.util.Calendar` to `java.time.LocalDate`
  (both modules are minSdk ≥ 26, so no desugaring needed). The rest of the Kotlin was already
  idiomatic (KTX, coroutines, scope functions, `runCatching`, Compose).

## 1.19 Lint-suppression cleanup (fix, don't suppress)

Removed every inline lint suppression in both languages by fixing the underlying cause, and pruned
the crate-wide allow list to only genuinely-justified entries.

- **Rust** (verified — 35 tests, clippy `-D warnings`, fmt): now **zero** `#[allow(clippy::…)]` /
  `#[expect(clippy::…)]` in `core/src`.
  - `redundant_pub_crate` (4 sites): fixed structurally — `util`'s helpers are `pub` in a private,
    un-re-exported module (crate-internal without the lint); `db`'s `DB_CONN`/`get_db_connection`
    are `pub` and deliberately kept out of the `pub use db::{…}` name list (tests reach `DB_CONN`
    via `crate::db::`).
  - `unused_async` (2 sites): removed the no-op `async_ops.rs` seed entirely (see §1.7).
  - proto.rs blanket `#![allow(pedantic, nursery)]`: dropped — the generated + hand-written code
    was already clean.
  - Cargo.toml allow list: dropped `option_if_let_else` (dead) and `too_long_first_doc_paragraph`
    (fixed the 5 long summary paragraphs by splitting them). Kept the FFI-justified ones
    (`needless_pass_by_value`, `missing_errors/panics_doc`, `cast_*`) and the genuinely-pervasive
    prose/FFI ones (`doc_markdown`, `must_use_candidate`, `missing_const_for_fn`) with rationale
    comments.
- **Kotlin** (unbuilt): now **zero** `@Suppress`.
  - `DEPRECATION` on `versionCode` → `PackageInfoCompat.getLongVersionCode()` (no deprecated call,
    no SDK check).
  - `UNUSED_VARIABLE` on the recomposition trigger → folded into `remember(session,
    cardCompletedTrigger) { session.getCurrentCard() }`.
  - `LongMethod`/`CyclomaticComplexMethod` on `fetchNewCards` → extracted `searchNewCardNoteIds` +
    `fetchSingleNewCard` (using `singleOrNull()`).
  - `LongMethod` on `ReviewCardScreen` → extracted `BoxScope.ReviewFront` / `BoxScope.ReviewAnswer`
    sub-composables. **Verify** the review flow still works after this extraction (state hoisting:
    reveal + grade go through the passed lambdas; `cardStartTime` is now a `val remember`).

---

# Part 2 — Remaining backlog (not implemented here)

Ordered roughly by leverage / independence. Each entry: current state, how to implement, how to
test.

## 2.1 Phone dark/light following system  — LIKELY ALREADY DONE, just verify

`phone/src/main/AndroidManifest.xml` already uses `Theme.Material3.DayNight.NoActionBar`, and the UI
reads colors via theme attributes (`MaterialColors.getColor(..., colorPrimary/colorError/...)`), not
hardcoded colors. So system dark/light should already work.
- **Test:** toggle the system theme (or battery-saver dark) and confirm the phone UI follows with no
  unreadable hardcoded colors. Audit `MainActivity` for any raw `Color.parseColor`/hex — currently
  none. If it already works, tick the box; otherwise the only likely fix is a stray hardcoded color.

## 2.2 Pre-push cap as a setting — PARTIALLY DONE

`prePushCap` (aka `MAX_PREPUSH_DECKS`) is already a persisted setting with a slider in the phone
Settings dialog and read by `SyncService`. **Only `MAX_SCAN_DECKS` (=60) remains a constant.** To
finish: add a `KEY_SCAN_CAP` setting mirroring the pre-push one (a second slider, or derive it), and
read it in `SyncService.runSync()` instead of the constant.

## 2.3 True pitch-accent CSS + inline images (full fidelity via phone-rendered bitmaps) — PARTIAL

Scaffolding already exists: `phone/.../CardBitmapRenderer.kt` renders card sides offscreen and ships
them; `wear/.../CardImageCache.kt` stores them; `wear/.../MainActivity.CardSideContent` prefers a
cached bitmap over `HtmlText`. What's likely missing / to verify:
- Offscreen `WebView` sizing to the watch's display width, correct DPI, and per-side caching keys.
- Media handling: the phone must resolve `[sound:]`-stripped HTML and actual `<img>`/`data:` media
  from the AnkiDroid collection dir and inline them before rendering.
- Pitch-accent CSS (overlines/borders) only renders correctly inside a WebView — so it must be part
  of the phone-side bitmap, never attempted via `HtmlCompat`.
- **Test:** a Kaishi card with pitch-accent overline and an image card. Bitmap must arrive and match
  what AnkiDroid shows; fall back to text cleanly when no bitmap.
- Read `CardBitmapRenderer.kt` / `CardImageCache.kt` first (not re-read in this session) to see how
  far it already goes before extending.

## 2.4 HtmlCompat honors only some inline CSS (e.g. `font-size:25px`) — mitigated, document limits

The watch text now has a **global** user font-scale (item 1.4). Per-span inline CSS sizes are still
subject to `HtmlCompat` limitations. Full per-span fidelity needs the WebView-bitmap path (2.3).
Decide whether the global scale is "good enough"; if not, this collapses into 2.3.

## 2.5 Scrolling / card-view performance — needs profiling on device

Areas to look at with the Compose layout inspector / `adb shell dumpsys gfxinfo`:
- `HtmlText` re-inflates a `TextView` in `update{}` on each recomposition; the `remember`ed
  cleaned/`html_for_display` strings already cut Rust calls. Consider `remember`ing the parsed
  `Spanned` too.
- The review card uses `verticalScroll` + `rotaryScroll` on a `Column`; confirm no nested infinite
  constraints (CLAUDE.md §5). Lists use `ScalingLazyColumn` with `autoCentering = null` already.
- Measure before/after; there's nothing to change blindly.

## 2.6 Phone UI → Material 3 Expressive Compose migration (+ animated folder tree) — BIG

The phone is still imperative Views/XML (`MainActivity` builds `LinearLayout`s in code) and
re-renders the whole deck tree on every toggle (`renderDeckTree()`), so there's no spring chevron
rotation or animated expand/collapse. This is a dedicated migration:
- Move `:phone` to Jetpack Compose + Material 3 (Expressive) as the watch did.
- Model the deck tree with `AnimatedVisibility`/`animateFloatAsState` for chevron rotation and
  expand/collapse, tonal emphasis for folders.
- Reuse `buildDeckTree`/`collectDeckIds` (already pure, in `MainActivity.kt`) — extract them to
  their own file so Compose and any test can share them.
- **Test:** deck selection state still persists (`deck_sync_prefs`), folder checkboxes still
  cascade, insets/edge-to-edge preserved (see 2.11).

## 2.7 Declarative Tiles with Compose for Wear OS — MEDIUM

`AnkiTileService` uses protolayout builders. Rebuild with Compose for Tiles (glanceable/interactive)
reading state from the Rust core. Keep the ETA logic (`format_queue_eta`) — just change the
rendering layer. **Test:** Tile still shows due count + ETA and updates after sync.

## 2.8 Progress-centric notifications (Android 16 `Notification.ProgressStyle`) — API-gated

`SyncService.buildNotification` uses `setProgress`. When you can require API 36, upgrade to
`ProgressStyle` for live-activity-style start→end tracking. Gate on `Build.VERSION.SDK_INT >= 36`
with the current bar as fallback. **Test:** run a multi-deck sync on Android 16, confirm the shade
shows the journey progress.

## 2.9 Edge-to-edge + predictive back — audit

- Phone already applies system-bar insets (`setOnApplyWindowInsetsListener` in `MainActivity`).
  Audit fully on Android 15/16 (status/nav overlap, gesture nav).
- Watch uses Compose `BackHandler`; verify it participates in **predictive back**
  (register via `OnBackInvokedDispatcher` / enable the manifest flag) so the OS can animate.
- **Test:** predictive-back gesture shows the ahead-of-time animation on both apps; no content under
  the bars.

## 2.10 Rotary dial (crown/bezel) verification — device-only

Code uses `Modifier.rotaryScrollable` (`MainActivity.rotaryScroll`). Confirm on a Pixel Watch
(crown) and Galaxy Watch (bezel) that **every** scrollable screen scrolls + haptics: deck picker,
deck screen, review card (front & answer), and the new **Settings screen**. Nothing to change unless
a screen doesn't grab focus (only one screen is composed at a time, so focus shouldn't fight).

## 2.11 Wear power best-practices checklist — review + likely small fixes

Against https://developer.android.com/training/wearables/apps/power:
- Wake locks / keep-screen-on during TTS + review — confirm we don't hold the screen on longer than
  needed.
- Foreground `SyncService` duration on the phone — it stops itself; confirm it never lingers.
- Data Layer batching (fewer, larger `DataItem`s) — gzip (1.2) already helps.
- Ambient / always-on behavior; minimize sensor/CPU during reviews.
- **Test:** Battery Historian / `dumpsys batterystats` around a review + sync session.

## 2.12 AGP 9.2.1 migration — BLOCKED, dedicated task

Currently AGP 8.13.2 / Gradle 8.14.3. Blockers (from the backlog): AGP 9 drops the separate Kotlin
Gradle plugin (needs "AGP built-in Kotlin", removing `org.jetbrains.kotlin.android`); needs Gradle
9.4.1+; ktlint/ktfmt/detekt need compat verification. Do this on its own branch, not bundled.

## 2.13 UniFFI async/suspend mapping — architectural

Modern UniFFI maps Rust `async fn` → Kotlin `suspend`. Today the apps wrap blocking FFI in
`withContext(Dispatchers.IO)`. Candidates to move fully into async Rust: networking, heavy
processing, future local vector search. Migrate incrementally; verify coroutine cancellation works.

## 2.14 Protobuf/FlatBuffers over the Data Layer instead of JSON — architectural

Swap `serde_json` in `serialize_due_batch`/`deserialize_due_batch` for a compact schema. Combined
with gzip (1.2) this shrinks payloads further and removes any Kotlin-side JSON. Keep the same
Kotlin-facing byte API so only `core/src/sync.rs` + schema change. **Test:** round-trip + size vs
current gzipped-JSON; mixed-version compat if staged.

## 2.15 `stripBase64Images` (phone) — leave in Kotlin (documented decision)

`AnkiDroidBridge.stripBase64Images` is a one-line regex. Rust has no `regex` dep; a hand-rolled
scanner isn't worth it. Keep in Kotlin unless 2.3/2.14 pull it into Rust anyway.

## 2.16 Kotlin/instrumented test coverage — the biggest test gap

- Extract `MainActivity` `@Composable`s (both apps) to top-level functions so Compose UI tests can
  drive real screens (`ComposeSmokeTest` in `wear/src/test` is only a scaffold).
- Unit-test `SyncService` progress math, `WearSyncService` asset/byte handling, phone deck-tree
  rendering (`buildDeckTree`/`collectDeckIds` are already pure and testable — start there).
- Add instrumented `androidTest` (Espresso / device) and a phone-sync → watch-review **E2E** flow.
- The FFI round-trip tests (`CoreFfiTest`) already prove the binding layer — extend them for
  `formatQueueEta` (added) and any new exports.

## 2.17 Tile ETA using buffer average — DONE (see 1.1). ✅

## 2.18 `ActivityManager: sent binder code 10 ... error -74` — investigate

Seen once during answer sync, no observed impact. Likely a benign Wearable/binder transaction race.
Reproduce with verbose Wearable logging during `/answers` → `/clear_answers`; only act if it
correlates with a real failure.

## 2.19 FFI memory/GC hygiene audit — partially addressed

`ReviewSession` now frees deterministically (1.3). Audit remaining loops that create many core
objects per-card. `Record` types (`WearosCard`, `WearosDueBatch`, `WearosAnswer`, deck records) are
plain Kotlin data classes (no handle) — safe. Only `#[uniffi::Object]` types hold handles; today
`ReviewSession` is the only one. If more Objects are added, give them the same `use {}`/`destroy()`
treatment.

---

## Quick status matrix

| Backlog item | Status |
|---|---|
| Tile ETA from real average | ✅ done (Rust verified, Kotlin unbuilt) |
| gzip in serialize/deserialize | ✅ done (Rust verified, Kotlin unbuilt) |
| ReviewSession explicit free / GC hygiene | ⚠️ implemented, verify binding name + no UAF |
| Watch settings menu | ⚠️ implemented, unbuilt |
| Watch light mode + follow system | ⚠️ implemented, **ColorScheme API is the top compile risk** |
| Watch fonts (size + family) | ⚠️ implemented, unbuilt |
| Rich formatting / font sizing | ⚠️ global scale done; per-span needs bitmap path |
| Phone read-only test mode | ⚠️ implemented, unbuilt |
| Automatic background deck sync | ⚠️ implemented (WorkManager, new dep), unbuilt |
| Grow Rust test coverage | ✅ done (verified) |
| Move pre-push cap into setting | partial pre-push already a setting; scan cap remains |
| Phone dark/light follow system | partial theme already DayNight — just verify |
| stripBase64Images to Rust | ⏸ intentionally kept in Kotlin |
| Pitch-accent CSS + images (bitmaps) | ⏸ scaffolding exists; extend + verify |
| HtmlCompat inline CSS sizes | ⏸ mitigated by global scale; else needs bitmaps |
| Scrolling/card perf | ⏸ needs on-device profiling |
| Phone animated deck tree | ◐ LayoutTransition stopgap; full Expressive tree = Compose migration |
| Phone M3 Expressive Compose migration | ◐ Compose enabled + animated deck tree component landed; not yet hosted (verify deps) |
| Compose Tiles rebuild | ◐ tile now interactive + auto-refreshing; full Glance/Compose rebuild pending |
| ProgressStyle notifications | ⏸ API 36 gated (needs compileSdk 36 bump) |
| Predictive back | ◐ manifest flag on; verify + optional PredictiveBackHandler |
| Edge-to-edge | ⏸ audit (phone insets already applied) |
| Rotary dial verification | ⏸ device-only |
| Power checklist | ⏸ device-only |
| AGP 9.2.1 migration | ⏸ blocked, dedicated task |
| UniFFI async/suspend | ◐ seed exports added (runtime-free); wire + tokio to offload |
| Protobuf/FlatBuffers | ◐ prost path added + tested; not wired into sync yet |
| binder code 10 / error -74 | ⏸ investigate |
| Kotlin + instrumented/E2E tests | ⏸ largest remaining test gap |
