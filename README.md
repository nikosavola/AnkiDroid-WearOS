# AnkiDroid WearOS Companion

An offline-first Wear OS smartwatch companion for [AnkiDroid](https://github.com/ankidroid/Anki-Android), designed to let you study and grade flashcards directly from your wrist.

---

## 1. Architecture Overview
This project uses a unified **Kotlin + Rust (via UniFFI)** hybrid architecture:

```mermaid
flowchart LR
    subgraph Phone["📱 Phone (Kotlin)"]
        AD["AnkiDroid<br/>collection.anki2 + rslib scheduler"]
        CR["ContentResolver"]
        Bridge["Mock / AnkiDroid Bridge<br/>+ WearSyncService"]
        AD <-->|FlashCardsContract IPC| CR
        CR <--> Bridge
    end
    subgraph Watch["⌚ Watch (Kotlin + Rust core)"]
        Core["Rust Core<br/>session · cache · SQLite"]
        UI["Compose review UI<br/>grade via rotary / buttons"]
        Core --> UI
        UI -->|buffered answers| Core
    end
    Bridge <-->|"DataClient (Bluetooth)"| Core
```

- **Core Logic in Rust (`core/`):** All platform-agnostic layers—the review session state machine, the SQLite offline answer buffer, JSON serialization, and the high-performance HTML tag-stripping/sanitizing parser—are built in Rust. It is compiled to native libraries (`.so`) using `cargo-ndk` and exported to Kotlin via **UniFFI** and **JNA**.
- **Platform Layers in Kotlin (`phone/` and `wear/`):** Kotlin subprojects handle UI (Jetpack Compose), local background synchronization (`WearSyncService`), Text-to-Speech (TTS), and system integrations (like the Wear OS Tile).
- **Communication Layer:** Uses Google Play Services `DataClient` to reliably sync pending card batches and user reviews back-and-forth over Bluetooth, surviving device disconnections.

---

## 2. Key Features

- **Offline-First Review Buffer:** The watch holds its own SQLite caching database. If you go offline or walk away from your phone, reviews are stored locally, sorted, and automatically pushed back to the companion phone app upon re-connection.
- **Responsive Wear OS Compose UI:** A gorgeous, circular-safe layout built with Wear OS Compose.
- **Dynamic Grading Grid:** Automatically adapts to 3-button or 4-button templates depending on the card's deck configuration, rendering color-coded chips with scheduled interval previews.
- **Text-to-Speech (TTS) Voice Reviews:** Toggling "Voice Reviews" on the dashboard activates system-native vocal readouts that read cards' front text on load and answer text on reveal.
- **System-Native Tiles:** Displays pending cards due today natively inside the smartwatch's quick-swipe carousel using Google's modern `androidx.wear.tiles` and `androidx.wear.protolayout` standards.
- **AnkiDroid Mock Fallback:** To facilitate testing in emulator or headless CLI environments, the phone bridge falls back to generating virtual cards and mocking scheduling operations if the real AnkiDroid app is not installed. Debug builds enable this automatically; release builds require opting in via the Force Mock Mode settings toggle.

---

## 3. Directory Layout

```
├── core/                # Rust shared core: state machine, local buffer, and serializers
├── bindings/            # Auto-generated Kotlin UniFFI binders (gitignored)
├── phone/               # Android Companion Phone App: ContentProvider client and sync service
└── wear/                # Android Wear OS Watch App: Jetpack Compose UI, Tile Service, and TTS
```

---

## 4. Getting Started & Compilation

### Prerequisites
1. **Java JDK 21:** Set as your active Gradle compiler JVM (`JAVA_HOME`). macOS: `brew install openjdk@21` then `JAVA_HOME=/opt/homebrew/opt/openjdk@21`; Linux: `/usr/lib/jvm/java-21-openjdk-amd64`. Any JDK 17-26 also works. See `CONTRIBUTING.md` for the full macOS emulator setup.
2. **Android NDK (r28+) & SDK:** Correctly installed on your local machine.
3. **Rust Android Targets:** Ensure your Rust toolchain can cross-compile to Android architectures:
   ```bash
   rustup target add aarch64-linux-android x86_64-linux-android
   ```

### Compile Rust & Generate Bindings
To cross-compile all Android native `.so` binaries (`arm64-v8a` and `x86_64`) and generate the Kotlin UniFFI bindings, run:
```bash
JAVA_HOME=<jdk21> ./gradlew generateBindings
```

### Full Kotlin Compilation
To compile both the phone companion and smartwatch projects, run:
```bash
JAVA_HOME=<jdk21> ./gradlew compileDebugKotlin
```

---

## 5. Verification & Testing

### Running Rust Core Tests
We host a complete Rust unit test suite verifying our SQLite buffered schemas, state-machine transitions, and HTML-cleansing layers. Run them locally via:
```bash
cargo test --manifest-path core/Cargo.toml
```

### Local Emulation
You can install both modules onto an Android Phone and a Wear OS circular emulator. The phone companion will automatically pair with the watch's sync listener and detect whether the local `com.ichi2.anki.flashcards` provider is available. Debug builds fall back to mock cards for testing when it is not; release builds need the Force Mock Mode toggle.

For a lightweight, headless phone environment (no KVM/AVD needed), `CONTRIBUTING.md` documents running Android in Docker via **redroid** — handy for verifying the AnkiDroid write-back path and phone UI on a server or remote VM.

## Published packages

`:ankidroid-provider` is a small Kotlin library for AnkiDroid's flashcard ContentProvider: deck
lists with due counts and options, AnkiDroid's own review queue, card scheduler state, Anki-search
card queries, and a write-back that confirms each answer actually landed. It has no Rust and no
dependencies beyond the Kotlin stdlib. Calls are blocking; make them off the main thread. Your app
needs AnkiDroid installed and the `com.ichi2.anki.permission.READ_WRITE_DATABASE` runtime grant.

Each `vX.Y.Z` tag publishes `fi.nikosavola:ankidroid-provider:X.Y.Z` to GitHub Packages. Reading
from GitHub Packages needs a classic personal access token with `read:packages`, even for public
packages:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
  repositories {
    maven {
      url = uri("https://maven.pkg.github.com/nikosavola/AnkiDroid-WearOS")
      credentials {
        username = System.getenv("GITHUB_ACTOR")
        password = System.getenv("GITHUB_TOKEN")
      }
    }
  }
}

// build.gradle.kts
dependencies { implementation("fi.nikosavola:ankidroid-provider:0.1.0") }
```

The same tags also build on [JitPack](https://jitpack.io) without a token, once the repository is
public: add `maven("https://jitpack.io")` and depend on
`com.github.nikosavola.AnkiDroid-WearOS:ankidroid-provider:v0.1.0`.

The library is LGPL-3.0-or-later (see `ankidroid-provider/COPYING.LESSER`), the same license as
AnkiDroid's own API library, so apps of any license can use it.

## License

AGPL-3.0-or-later, the same license as Anki itself; see [LICENSE](LICENSE). The exception is the
`ankidroid-provider/` library, which is LGPL-3.0-or-later (`ankidroid-provider/COPYING.LESSER`,
which supplements the GPL in `ankidroid-provider/COPYING`). `core/vendor/anki/` holds files from Anki's
`rslib` verbatim (Copyright Ankitects Pty Ltd and contributors, AGPL-3.0-or-later; pinned in
`core/vendor/anki/UPSTREAM.toml`, checked by `scripts/vendor-rslib.sh --check`), and the queue
builder in `core/src/pool.rs` ports logic from it.

The phone app bundles [MathJax](https://www.mathjax.org/) 3.2.2 (Apache-2.0, see
`phone/src/main/assets/mathjax/LICENSE`).
