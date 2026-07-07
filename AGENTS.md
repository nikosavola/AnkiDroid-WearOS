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
- **Watch Sync Service (`:wear`):** Intercepts incoming `/due_batch` payloads from the phone, validates and deserializes them in Rust, and saves them locally as `due_batch.json` in local watch storage.

---

## 4. Build and Compilation Workflows

### Prerequisites
- **Android SDK & NDK (r28+)** are required for compilation.
- **Java JDK 21** must be set as the default compiler JVM.

### Automatic Binder & Library Compilation
To cross-compile the Rust `.so` libraries for Android (`arm64-v8a`, `x86_64`) and generate the Kotlin bindings in one command, run:
```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew generateBindings
```

### Full Verification Compile
To verify that all Android subprojects and Kotlin layouts compile without error, run:
```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew compileDebugKotlin
```

### Rust Core Unit Testing
To run self-contained, offline unit tests for our SQLite cache, state-machine transitions, and serializer layers, execute:
```bash
cargo test --manifest-path core/Cargo.toml
```

---

## 5. Card Rendering (native, no WebView)
AnkiDroid's `CardContentProvider` compiles flashcard templates and outputs HTML. Wear OS devices
frequently ship **no WebView provider** (e.g. Galaxy Watch), so constructing a `WebView` throws
`UnsupportedOperationException` and crashes the app. We therefore render cards natively.
- **Rust `clean_html`:** sanitizes the card HTML for display and TTS: strips tags, removes HTML
  comments (Anki hides fields like `{{Pitch Accent}}` inside them) and `[sound:...]` refs, and
  converts furigana `<ruby>base<rt>reading</rt></ruby>` to `base(reading)`.
- **Compose rendering:** the watch shows the cleaned text in a weighted, vertically-scrolling
  `Column` (never a nested scrollable that passes infinite constraints). Grade buttons are a
  centered 2x2 grid so all of Again/Hard/Good/Easy stay inside the round display.
- **Not yet supported natively:** CSS-styled pitch-accent overlines and images. True fidelity for
  those would require rendering on the phone (which has a WebView) into a bitmap sent to the watch.

---

## 6. Stand-Alone Refresh Handshake & Selective Sync
- **Refresh Handshake (`/request_decks`):** To support independent Wear OS operation, the watch can broadcast a `/request_decks` refresh message over Bluetooth. The phone background service intercepts this, queries the available decks, applies user-selected filters, and pushes back a filtered `/decks` dataset asynchronously.
- **Selective Sync Configuration:** The phone companion app displays a dynamic, scrollable checkbox list of all available AnkiDroid decks. Checkbox preferences are persisted in `SharedPreferences`, and both background syncs and manual syncs filter out unchecked decks, keeping the watch's deck list clean and uncluttered.

---

## 7. Unified Code Formatting and Linting
- **Modern Kotlin Tooling Suite:** The project standardizes on `ktfmt` (Facebook/Google style) as the sole formatter, and `ktlint` as the code-quality linter. Style conflicts (such as `function-signature` and `parameter-list-wrapping` rules) are resolved via custom `.editorconfig` rules, with `ktfmt` operating as the absolute formatting authority.
- **Parallel Pre-commit Runner (`prek`):** A `.pre-commit-config.yaml` file configures local format and lint tasks on concurrent pipelines using priority keys (formatting at `priority: 10` and linting at `priority: 20`), executed via:
```bash
uv run prek run --all-files
```
