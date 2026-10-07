# AnkiDroid WearOS Companion — Developer & Architecture Guide

This file outlines the shared architecture conventions, tech stack standards, and compilation workflows for the AnkiDroid WearOS project.

---

## 1. Architectural Principles
- **Rust First:** Put all logical cores, state machines, offline database caching, and sync protocols inside our shared `core` Rust crate.
- **Kotlin for Platform Only:** Kotlin subprojects (`:phone` and `:wear`) must only handle UI (Jetpack Compose), GMS Wearable interactions, and native Android IPC interfaces (like the ContentProvider client).
- **Offline Durability:** The watch app must remain completely functional offline. Card reviews must be buffered within the local SQLite database managed by the Rust core before being pushed back to the companion phone app.

---

## 2. Multi-Language Binding Contract (UniFFI & JNA)
We use **UniFFI** and **JNA** to auto-generate safe Kotlin interfaces from our Rust core:
- **Automatic CamelCase Conversion:** Rust's snake_case property/method names are translated into camelCase inside Kotlin. Ensure your Kotlin code targets `deckName`, `buttonCount`, `revealAnswer()`, and `answerCard()`.
- **Direct ByteArray Mappings:** UniFFI maps Rust `Vec<u8>` structures directly into `ByteArray` inside Kotlin, avoiding translation overhead.
- **Unified Error Handling:** Native Rust errors are compiled as subclasses of Kotlin's `Throwable`. Rust `CoreError` variants use `{ error_message: String }` instead of `{ message: String }` to prevent colliding with Kotlin's built-in `Throwable.message` getter.

---

## 3. Passive Background Sync Architecture
- **Companion Sync Service (`:phone`):** A passive background `WearSyncService` intercepts watch grades on the `/answers` data layer path, writes them back to AnkiDroid (see §7), and replies with `/clear_answers` (ids that settled) and `/unplaced_answers` (ids it examined but couldn't place). Only unplaced reports count toward the watch's orphan prune (8 reports and at least 3 days old); launches and offline sends never do.
- **Watch Sync Service (`:wear`):** Intercepts incoming `/due_batch` pools from the phone, validates and deserializes them in Rust, and stores them in the on-watch SQLite card pool (`core/src/pool.rs`), so several decks stay reviewable offline for days.
- **Answer upload (`:wear`):** grades are handed to the Data Layer by the `answer-upload` WorkManager job, 20s after the last grade and immediately when the app stops. The Data Layer queues the item until the phone is reachable.

---

## 4. Build and Compilation Workflows

### Prerequisites
- **Android SDK & NDK** are required for compilation: SDK platform 36, build-tools 36.x, and the pinned `ndk;27.0.12077973` (install via `sdkmanager`; SDK lives at `~/Library/Android/sdk` on macOS, `~/Android/Sdk` on Linux).
- **JDK 21** is the default `JAVA_HOME` for Gradle (what CI uses, and it matches the apps' `VERSION_21` bytecode target). macOS: `brew install openjdk@21` then `JAVA_HOME=/opt/homebrew/opt/openjdk@21`; Linux: `/usr/lib/jvm/java-21-openjdk-amd64`. Any JDK 17-26 also works as the launcher JVM — Gradle 9.4 supports Java up to 26, and the foojay toolchain resolver (1.0.0+) auto-provisions the toolchain. Apps still compile to Java 21 bytecode regardless.

### Automatic Binder & Library Compilation
To cross-compile the Rust `.so` libraries for Android (`arm64-v8a`, `x86_64`) and generate the Kotlin bindings in one command, run:
```bash
JAVA_HOME=<jdk21> ./gradlew generateBindings
```

### Full Verification Compile
To verify that all Android subprojects and Kotlin layouts compile without error, run:
```bash
JAVA_HOME=<jdk21> ./gradlew compileDebugKotlin
```

### Rust Core Unit Testing
To run self-contained, offline unit tests for our SQLite cache, state-machine transitions, and serializer layers, execute:
```bash
cargo test --manifest-path core/Cargo.toml
```

### Kotlin Unit Tests (phone + wear)
Host-JVM tests (JUnit + Robolectric + MockK + Compose-under-Robolectric); no emulator or device
needed, and CI runs them on every push/PR alongside the Rust tests:
```bash
JAVA_HOME=<jdk21> ./gradlew :phone:testDebugUnitTest :wear:testDebugUnitTest
```
The full Rust FFI is callable from these tests: JNA loads the host-built `libwearos_core.so`
(`compileRustHost` + `jna.library.path`), so serializers and the review session run for real, not
mocked. Key suites and what they guard:
- `WriteBackIntegrationTest` — real ContentResolver against a fake AnkiDroid scheduler provider:
  prime-then-answer contract, multi-deck drain, current-card gating. The heaviest CI-runnable slice
  of the sync; the watch<->phone GMS Data Layer hop itself cannot run headless (needs Play Services
  + two paired devices) and is verified manually on hardware.
- `AnswerSyncE2ETest` / `AnkiDroidBridgeTest` — wire round-trip fidelity, read-only mode, re-send
  dedupe (never double-apply), settle-vs-retry semantics.
- `WearSyncServiceTest` — never send clear_answers for grades that didn't settle.
- `GradeEdgeButtonsTest` / `FinishedScreenTest` / `DeckListItemsTest` / `HtmlTextTest` — Compose
  render + touch tests driving the real composables under Robolectric (NATIVE graphics).

### Clean Up Daemons After Toolchain Changes
Gradle and Kotlin each spawn a long-lived background daemon (`GradleDaemon`, `KotlinCompileDaemon`)
per distinct version they run. Bumping `gradle-wrapper.properties` or the Kotlin version during
experimentation leaves the *old* daemons running indefinitely in the background, each holding
`-Xmx2048m`. Run `./gradlew --stop` after a toolchain bump (it only stops daemons matching the
wrapper version in this project), and check `ps aux | grep java` for stray daemons from versions
you tried and reverted — kill those directly since `--stop` won't reach them.

---

## 5. Interval Previews (offline, AnkiDroid still owns scheduling)

AnkiDroid computes the real schedule (rslib: SM-2 or FSRS) and ships per-button interval labels with
each due batch — those are exact and are what the watch uses first. They go stale the moment a card
is graded and requeued inside a session: the requeued card keeps the labels fetched before the
grade, so a card that advanced a learning step or graduated would still show pre-grade numbers.

`core/src/sched.rs` recomputes them offline from what the deck exposes through the ContentProvider:

- **Config** comes from the deck's `OPTIONS` payload (`FlashCardsContract.Deck`): `new.delays` /
  `lapse.delays` (learning steps, stored in **minutes**), `new.ints` (graduating/easy days),
  `rev.*` factors, and the FSRS keys (`fsrsParams6`, `desiredRetention`). AnkiDroid 2.25.1 sends
  these in camelCase; the parser also accepts snake_case. Check a real payload before touching it.
- **Per-card state** comes from `FlashCardsContract.Card`: `queue`, `interval`, `left`
  (`left % 1000` = reps before graduation, which gives the learning-step index), `fsrs_stability`,
  `fsrs_difficulty`, `last_review_time_secs`.
- **Math**: learning/relearning steps are Anki's ladder (Again restarts it, Hard is Anki's hard
  delay — the average of the first two steps on step 1, otherwise a repeat — Good advances and
  graduates from the last step, Easy graduates immediately). Day-or-longer intervals come from
  FSRS-6 through the `fsrs` crate, the same one rslib uses (6.x dropped Burn, so it adds only a
  few KB to the watch `.so`), with rslib's floors on top: each passing button at least a day above
  the previous one, and a grown interval never fuzzed back below the current one. Reference values
  in the tests come from py-fsrs 6. Decks without weights follow Anki's SM-2 review
  rules ported from rslib `review.rs`: the card's own ease factor (fetched from `sm2_factor`) and
  its days-late drive Hard/Good/Easy, each floored above the previous button and bounded by the
  deck's maximum interval, with Anki's separate early-review branch when a card is answered ahead
  of schedule. A lapsed card returns to Anki's failing review interval (its old interval reduced by
  the lapse multiplier, never below the minimum lapse interval), not the graduating interval.
- **Session use**: `ReviewSession` advances the card's state on each grade and recomputes the
  requeued copy's labels, so a card worked through its ladder shows the numbers for the state it is
  actually in. Without a config (older phone build, mock cards) the shipped labels are left alone.

AnkiDroid remains the authority: previews are advisory and every grade is reconciled by AnkiDroid
on write-back. The one deliberate difference is **fuzz**: Anki fuzzes intervals of 2.5 days and up
into a window and its load balancer picks a day inside it, while these previews are deterministic.
The window is rslib's `fuzz_delta`: one day plus 15% of the days between 2.5 and 7, 10% of those
between 7 and 20 and 5% beyond, so a 25d Good can show anywhere from 22d to 28d.

Agreement is checked on-device by `AnkiDroidProviderLiveTest` (shadow mode): it inserts a real card
and compares every button label against AnkiDroid's own, which is what caught the new-card step
bug and Hard's first-step rule. Only due cards expose labels, so the graduated/relearning states
are covered by unit tests rather than that walk. Nothing in the modelled subset is knowingly
unmatched today. The live test allows exactly that fuzz window plus the label's rounding, and
counts elapsed time in Anki day numbers (a card reviewed late yesterday is one day old), which is
what a late SM-2 review's Good and Easy depend on.

## 6. Card Rendering (native, no WebView)
AnkiDroid's `CardContentProvider` compiles flashcard templates and outputs HTML. Wear OS devices
frequently ship **no WebView provider** (e.g. Galaxy Watch), so constructing a `WebView` throws
`UnsupportedOperationException` and crashes the app. We therefore render cards natively.
- **Rust `clean_html`:** sanitizes the card HTML for display and TTS: strips tags, removes HTML
  comments (Anki hides fields like `{{Pitch Accent}}` inside them) and `[sound:...]` refs, and
  converts furigana `<ruby>base<rt>reading</rt></ruby>` to `base(reading)`.
- **Compose rendering:** the watch shows the cleaned text in a weighted, vertically-scrolling
  `Column` (never a nested scrollable that passes infinite constraints). Grade buttons are a
  centered 2x2 grid so all of Again/Hard/Good/Easy stay inside the round display.
- **Rich fidelity via phone-rendered bitmaps:** CSS-styled pitch-accent overlines and images can't
  render natively, so cards that need them (`CardBitmapRenderer.needsRichRendering`) are rendered on
  the phone (which has a WebView) into a PNG, streamed to the watch as a per-card Data Layer asset,
  cached (`CardImageCache`), and shown by `CardSideContent` in place of the cleaned text. The native
  text path above remains the fallback when no bitmap is cached.

---


## 7. Offline Multi-Day Reviewing (watch-owned queue, AnkiDroid reconciles)

The watch is meant to work for days without the phone. Under the current API it owns *what is due
when*; AnkiDroid still owns the real schedule and re-derives it on write-back.

- **Pool (phone, `AnkiDroidBridge.fetchDuePool`)**: per deck, today's scheduler batch (AnkiDroid's
  exact labels), intraday learning cards (`due_at_secs`), cards from `prop:due=k` searches on the
  `cards` URI for k = 1..7 (`due_day`, an absolute Anki day), and `new.perDay * 7` new cards in
  position order. Capped at 400 cards; rich bitmaps are rendered only for near-term cards. Older
  AnkiDroid builds without the `cards` URI get today's batch only.
- **Day model (`core/src/day.rs`)**: Anki day numbers with a rollover hour. AnkiDroid doesn't expose
  the collection's rollover yet (ankidroid/Anki-Android#20773), so both sides use Anki's default 4am.
- **Today's queue (`pool_today_batch`)**: learning cards within Anki's 20-minute learn-ahead, then
  due and overdue reviews up to `rev.perDay`, then new cards up to `new.perDay`. The limits count
  local grades per Anki day (`grade_log`). `ReviewSession.fromPool` builds the session; grading
  advances the card with `sched.rs` and stores its next due day or due time, and undo reverts it.
  A fresh pool from the phone replaces stored cards except those with answers still buffered.
- **Write-back timing**: AnkiDroid's `ReviewInfo` update applies each answer *as of sync time*
  (`answeredAtMillis = now`); there is no way to pass the real review time. Reviews synced days later
  are therefore rescheduled from the sync day (FSRS-6 defaults, S=10, Good: due on day 32 per the
  watch, day 39 in AnkiDroid when synced 3 days later). The early upload above keeps that gap
  small. Issue #32 tracks the upstream `answered_at` fix.
- **Write-back order**: rslib only rejects a non-head answer ("not at top of queue") while its study
  queues are built, and the provider swallows that error, still reports one row, and spends seconds
  filing a crash report. Selecting another deck and then the original one drops the queues, after
  which answers apply by (note_id, ord) in any order, learning chains included. `writeAnswersBack`
  does that toggle once, answers oldest-first, confirms each by re-reading the card's `reps`,
  re-toggles and retries once if the queues were rebuilt, and restores the selected deck. A
  single-deck collection falls back to head-gated draining. Verified on AnkiDroid 2.25.1.

---

## 8. Stand-Alone Refresh Handshake & Selective Sync
- **Refresh Handshake (`/request_decks`):** To support independent Wear OS operation, the watch can broadcast a `/request_decks` refresh message over Bluetooth. The phone background service intercepts this, queries the available decks, applies user-selected filters, and pushes back a filtered `/decks` dataset asynchronously.
- **Selective Sync Configuration:** The phone companion app displays a dynamic, scrollable checkbox list of all available AnkiDroid decks. Checkbox preferences are persisted in `SharedPreferences`, and both background syncs and manual syncs filter out unchecked decks, keeping the watch's deck list clean and uncluttered.

---

## 9. Unified Code Formatting and Linting
- **Modern Kotlin Tooling Suite:** The project standardizes on `ktfmt` (Facebook/Google style) as the sole formatter, and `ktlint` as the code-quality linter. Style conflicts (such as `function-signature` and `parameter-list-wrapping` rules) are resolved via custom `.editorconfig` rules, with `ktfmt` operating as the absolute formatting authority.
- **Parallel Pre-commit Runner (`prek`):** A `.pre-commit-config.yaml` file configures local format and lint tasks on concurrent pipelines using priority keys (formatting at `priority: 10` and linting at `priority: 20`), executed via:
```bash
uv run prek run --all-files
```
