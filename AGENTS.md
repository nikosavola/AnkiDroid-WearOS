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
- **Companion Sync Service (`:phone`):** A passive background `WearSyncService` intercepts watch grades on the `/answers` data layer path, processes them via Rust, and writes them back to AnkiDroid's content database. It returns a `/clear_answers` confirmation message back to the watch.
- **Watch Sync Service (`:wear`):** Intercepts incoming `/due_batch` payloads from the phone, validates and deserializes them in Rust, and saves them locally per-deck as `deck_<id>.json` in local watch storage (so multiple decks can be cached and switched offline).

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
  `rev.*` factors, and the FSRS keys (`fsrs_params_6`, `desired_retention`). The dict mixes camelCase
  and snake_case — read rslib's `deckconfig/schema11.rs` before touching the parser.
- **Per-card state** comes from `FlashCardsContract.Card`: `queue`, `interval`, `left`
  (`left % 1000` = reps before graduation, which gives the learning-step index), `fsrs_stability`,
  `fsrs_difficulty`, `last_review_time_secs`.
- **Math**: learning steps are a plain ladder; day-or-longer intervals come from FSRS-6 via the
  `rs-fsrs` crate (chosen over the `fsrs` crate rslib uses — same formula family, no
  ndarray/rayon/Burn dependency in a watch `.so`). Decks without weights fall back to SM-2 factors.
- **Session use**: `ReviewSession` advances the card's state on each grade and recomputes the
  requeued copy's labels, so a card worked through its ladder shows the numbers for the state it is
  actually in. Without a config (older phone build, mock cards) the shipped labels are left alone.

AnkiDroid remains the authority: previews are advisory, every grade is reconciled by AnkiDroid on
write-back, and the previews are unfuzzed where AnkiDroid's own labels are fuzzed. Agreement with
AnkiDroid is checked on-device by `AnkiDroidProviderLiveTest` (shadow mode); outstanding gaps in the
modelled subset are tracked in issue #24.

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

## 7. Stand-Alone Refresh Handshake & Selective Sync
- **Refresh Handshake (`/request_decks`):** To support independent Wear OS operation, the watch can broadcast a `/request_decks` refresh message over Bluetooth. The phone background service intercepts this, queries the available decks, applies user-selected filters, and pushes back a filtered `/decks` dataset asynchronously.
- **Selective Sync Configuration:** The phone companion app displays a dynamic, scrollable checkbox list of all available AnkiDroid decks. Checkbox preferences are persisted in `SharedPreferences`, and both background syncs and manual syncs filter out unchecked decks, keeping the watch's deck list clean and uncluttered.

---

## 8. Unified Code Formatting and Linting
- **Modern Kotlin Tooling Suite:** The project standardizes on `ktfmt` (Facebook/Google style) as the sole formatter, and `ktlint` as the code-quality linter. Style conflicts (such as `function-signature` and `parameter-list-wrapping` rules) are resolved via custom `.editorconfig` rules, with `ktfmt` operating as the absolute formatting authority.
- **Parallel Pre-commit Runner (`prek`):** A `.pre-commit-config.yaml` file configures local format and lint tasks on concurrent pipelines using priority keys (formatting at `priority: 10` and linting at `priority: 20`), executed via:
```bash
uv run prek run --all-files
```
