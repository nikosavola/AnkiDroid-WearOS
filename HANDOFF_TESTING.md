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
| Grow Rust test coverage | ✅ done (verified) |
| Move pre-push cap into setting | partial pre-push already a setting; scan cap remains |
| Phone dark/light follow system | partial theme already DayNight — just verify |
| stripBase64Images to Rust | ⏸ intentionally kept in Kotlin |
| Pitch-accent CSS + images (bitmaps) | ⏸ scaffolding exists; extend + verify |
| HtmlCompat inline CSS sizes | ⏸ mitigated by global scale; else needs bitmaps |
| Scrolling/card perf | ⏸ needs on-device profiling |
| Phone M3 Expressive Compose + animated tree | ⏸ big migration |
| Compose Tiles rebuild | ⏸ |
| ProgressStyle notifications | ⏸ API 36 gated |
| Edge-to-edge + predictive back | ⏸ audit |
| Rotary dial verification | ⏸ device-only |
| Power checklist | ⏸ device-only |
| AGP 9.2.1 migration | ⏸ blocked, dedicated task |
| UniFFI async/suspend | ⏸ architectural |
| Protobuf/FlatBuffers | ⏸ architectural |
| binder code 10 / error -74 | ⏸ investigate |
| Kotlin + instrumented/E2E tests | ⏸ largest remaining test gap |
