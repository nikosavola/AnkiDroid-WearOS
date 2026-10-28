# Wear for AnkiDroid - Developer & Architecture Guide

This file outlines the shared architecture conventions, tech stack standards, and compilation workflows for the Wear for AnkiDroid project.

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
- **Android SDK & NDK** are required for compilation: SDK platform 37.2, build-tools 37.x, and the pinned `ndk;27.0.12077973` (install via `sdkmanager`; SDK lives at `~/Library/Android/sdk` on macOS, `~/Android/Sdk` on Linux).
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
- **Math is Anki's own code**: rslib's `scheduler/states` (all 13 files) and `scheduler/timespan.rs`
  are vendored **verbatim** from Anki 26.05 (`e64c6b1`, the rslib AnkiDroid 2.25.1 bundles) under
  `core/vendor/anki`, with Anki's AGPL `LICENSE` and an `UPSTREAM.toml` of sha256s. Never edit them;
  CI runs `scripts/vendor-rslib.sh --check`, and bumping Anki is `scripts/vendor-rslib.sh --update
  <commit> <tag>` plus a review of the diff. The `core/anki-states` crate (a path dependency, not a
  workspace member, so the vendored `#[cfg(test)]` modules never build) compiles them against
  type-check-only stand-ins for the rest of rslib; its collection/load-balancer stubs are
  `unreachable!()`. `sched.rs` is the adapter, ported from rslib `scheduler/answering`: card fields
  to `CardState` (`current.rs`), FSRS outcomes, short-term flag and fuzz factor (`mod.rs`), and the
  chosen state back to card fields (`learning.rs`/`review.rs`/`relearning.rs`). Labels come from
  `timespan.rs` (a month is 365/12 days; hours, months and years keep one decimal). FSRS uses the
  `fsrs` crate 6.6.2 (Burn-free; Anki 26.05 pins 5.2.0, same `NextStates` API).
- **Session use**: `ReviewSession` advances the card's state on each grade and recomputes the
  requeued copy's labels, so a card worked through its ladder shows the numbers for the state it is
  actually in. Without a config (older phone build, mock cards) the shipped labels are left alone.
- **FSRS without deck weights**: AnkiDroid 2.25 schedules FSRS but ships no FSRS keys in the deck
  `OPTIONS` payload, so a no-weights deck whose card carries a stored memory state
  (`fsrs_stability` > 0) is scheduled with the `fsrs` crate's default weights, the card's own
  `fsrs_desired_retention`/`fsrs_decay` columns overriding the default retention and decay. A card
  with no memory state in such a deck keeps SM-2 labels.

AnkiDroid remains the authority: previews are advisory and every grade is reconciled by AnkiDroid
on write-back. **Fuzz** is reproduced: the phone ships rslib's seed (`fuzz_seed`, card id + reps)
and `sched.rs` makes rslib's `get_fuzz_factor` draw with the same `rand` 0.9. Known gaps: the
**load balancer** (on by default; for intervals up to 90 days it picks the least busy day in the
fuzz window from other cards' due dates, which the watch doesn't have), the rollover conversion of
day-long learning steps, a memory state Anki would rebuild from the revlog, and `left`, which the
phone ships as a step index (`sched.rs` turns it back into steps remaining).

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
- **Compose rendering:** the watch shows the display HTML as Compose text
  (`AnnotatedString.fromHtml`, no interop `TextView`) in a vertically-scrolling `Column` (never a
  nested scrollable that passes infinite constraints). Grade buttons are opaque curved segments
  along the bottom bezel, tapped through one strip that shares its gestures with the scrolling
  answer, so a swipe starting over the buttons still scrolls.
- **Reviewer filters, as AnkiDroid applies them (phone side, `AnkiDroidBridge.sidesOf`):** the
  provider leaves `{{type:Field}}` as a raw `[[type:Field]]` and sends no note type stylesheet. The
  bridge fills the placeholder from the note's field the way AnkiDroid does when nothing was typed
  (`core/src/typeanswer.rs`), and writes the note type's simple class rules inline, night-mode
  rules first (`core/src/css.rs`), so the CSS-less watch renderer still shows cloze styling.
- **Rich fidelity via phone-rendered bitmaps:** images, inline SVG, CSS overlines and borders can't
  render natively, so cards that need them (`CardBitmapRenderer.needsRichRendering`) are rendered on
  the phone (which has a WebView) into a PNG, streamed to the watch as a per-card Data Layer asset,
  cached (`CardImageCache`), and shown by `CardSideContent` in place of the cleaned text. The native
  text path above remains the fallback when no bitmap is cached. The decision reads CSS declarations
  in the card HTML only (inline styles or a `<style>` block in a field; the provider does not send
  the note type's stylesheet); never match a deck, note type or class name.
- **Bitmap page matches AnkiDroid's card WebView:** the body gets `card card{ord+1} nightMode
  night_mode` (the watch is always dark) and the note type's CSS (not in the provider's card HTML;
  the caller passes it to `syncBitmapsForBatch(batch, noteCss)`) is injected after the base styles
  as in AnkiDroid's card template, so its `.nightMode` overrides apply while AnkiDroid's
  `body.night_mode` colors win over `.card`, on a transparent background. The note CSS never
  feeds the bitmap gate. Sides with Anki's MathJax delimiters (`\(..\)`, `\[..\]`, never `$`) go
  through the bitmap path and load the bundled MathJax 3 SVG build
  (`phone/src/main/assets/mathjax/`, no fonts needed); capture waits until the
  `mathjax-needs-to-render` body class is removed after typesetting.

### Keeping it smooth
Compose already draws through the GPU (HWUI on the RenderThread); what costs frames on a watch is
main-thread work and redrawing the same chrome every frame. The rules the review screen follows:
- **No IO or decoding in composition.** `CardBitmaps` decodes card PNGs on `Dispatchers.IO`,
  scaled to the screen width, straight into `Bitmap.Config.HARDWARE` (uploaded to a GPU texture on
  the decoding thread), and prefetches the next cards via `ReviewSession.upcomingCardIds`.
- **Per-card text parsed off the transition frame.** The Rust HTML passes (answer split, display
  HTML, plain text) for upcoming cards run on a background dispatcher via `ReviewSession.upcomingCards`
  (`ParsedCards`), so the frame that shows a card only swaps already-built strings; a cache miss
  parses inline, which is the first card of a session.
- **Static chrome in one cached layer.** The progress ring, curved labels and grade segments sit in
  a single `Modifier.cachedLayer()` (an offscreen `graphicsLayer`) per side, so scrolling the card
  composites a texture instead of re-rasterizing arcs and rotated glyphs. Keep it to one layer per
  screen: each extra full-screen layer is another full-screen fill (check with
  `adb shell setprop debug.hwui.overdraw show`).
- **No native calls in draw lambdas.** Read session state once per grade
  (`remember(session, cardCompletedTrigger)`).
- **Curved text without warping.** `WearApp` turns off
  `WearComposeFoundationFlags.isWarpingCurvedTextEnabled`: warped curved text (the API 34+ default)
  builds a `Path` per glyph on the main thread and fills it with stencil-and-cover on the GPU. Plain
  curved text draws from the glyph cache and looks the same at our label sizes.
- **No symbol glyphs in `Text`.** A character the default font lacks (an arrow, say) loads a
  fallback font on the frame that first shows it; use a vector drawable instead.

To profile, build `:wear:assembleBenchmark` (R8, not debuggable, debug-signed, `profileable`), seed
a deck with `cargo run --example mock_deck` pushed as `files/deck_<id>.json` from the debug build
(`run-as`), install the benchmark APK over it, then compile with its baseline profile
(`am broadcast -a androidx.profileinstaller.action.INSTALL_PROFILE <pkg>/androidx.profileinstaller.ProfileInstallReceiver`
and `cmd package compile -f -m speed-profile <pkg>`). Read `dumpsys gfxinfo <pkg>` and a perfetto
trace (`gfx view input sched`); on the emulator, `dequeueBuffer` waits are the host compositor, not
the app. Debug builds are far too slow to profile meaningfully.

On a real watch (Galaxy Watch8: 60 Hz, Mali GPU, HWUI on Vulkan) the card transition is the one
frame that still runs long (recompose plus the first draw of the new card), while scrolling holds
vsync. Two harness gotchas there: a swipe that starts near the top edge opens the system panel and
later taps land on system UI, so start swipes well inside the screen and check
`dumpsys window | grep mCurrentFocus` between steps; and `simpleperf record --app <pkg> -g` works on
the profileable build (de-obfuscate with `stackcollapse.py --proguard-mapping-file` and the
benchmark `mapping.txt`). Card image fixtures should be 8-bit sRGB PNGs like the phone's
`Bitmap.compress` output; a 16-bit or cHRM-tagged PNG adds a per-pixel color conversion that real
cards never pay.

---


## 7. Offline Multi-Day Reviewing (watch-owned queue, AnkiDroid reconciles)

The watch is meant to work for days without the phone. Under the current API it owns *what is due
when*; AnkiDroid still owns the real schedule and re-derives it on write-back.

- **Pool (phone, `AnkiDroidBridge.fetchDuePool`)**: per deck, AnkiDroid's own queue for today
  (the schedule query asked for the deck's whole `deck_count`, up to 200 cards, in AnkiDroid's order
  with its exact labels, each card marked `queued_day`), intraday learning cards (`due_at_secs`),
  cards from `prop:due=k` searches on the `cards` URI for k = 1..7 (`due_day`, an absolute Anki
  day), and `new.perDay * 7` new cards in position order. The batch's `queue_day` says the queue
  for that day is complete (the counts vouch for it). `new.perDay` is the deck's effective limit:
  the provider only exposes the preset's, so when the limit binds it is read off today's queue as
  new cards queued plus `introduced:1`. Capped at 400 cards; rich bitmaps are rendered only for
  near-term cards. Older AnkiDroid builds without the `cards` URI get today's batch only.
- **Day model (`core/src/day.rs`)**: Anki day numbers with a rollover hour. AnkiDroid doesn't expose
  the collection's rollover yet (ankidroid/Anki-Android#20773), so both sides use Anki's default 4am.
- **Today's queue (`pool_today_batch`)**: learning cards within Anki's 20-minute learn-ahead, then
  AnkiDroid's queued cards. On the `queue_day` that queue is the whole day, since AnkiDroid already
  applied every limit including deck-level overrides. On other days the watch builds the main queue
  the way rslib v3 does: interday learning (gathered first) and reviews share `rev.perDay`, new
  cards get `new.perDay`, and both are merged into the reviews per `interdayLearningMix` /
  `newMix` with rslib's interspersing. The limits count local grades per Anki day (`grade_log`).
  Not modelled: sibling burying (`new.bury` / `rev.bury`, off by default) and
  `newCardsIgnoreReviewLimit` (a collection setting the provider doesn't expose; new cards are never
  capped by the review limit). `ReviewSession.fromPool` builds the session; grading
  advances the card with `sched.rs` and stores its next due day or due time, and undo reverts it.
  As in Anki, a requeued learning card that isn't due yet waits behind the remaining new and review
  cards, and is shown early (learn-ahead) only once nothing else is left.
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

---

## 10. AppFunctions (assistant-callable actions, Android 16+)
Both apps expose [AppFunctions](https://developer.android.com/ai/appfunctions) on the alpha
`androidx.appfunctions` line (KSP compiler, `aggregateAppFunctions=true`). Each app has one
`@AppFunctionServiceEntryPoint` base class (`phone/.../appfunctions/PhoneAppFunctions.kt`,
`wear/.../appfunctions/WatchAppFunctions.kt`); KSP generates the concrete service, which the manifest
declares with `android:enabled="@bool/enablePlatformAppFunctionService"` so it only runs on API 36+.
- **Phone:** `listDecks`, `setDeckSyncedToWatch`, `syncWatchNow` (enqueues the sync worker).
- **Watch:** `listCachedDecks`, `startReview` (returns a per-deck `PendingIntent` into the deck
  screen), `refreshDeck` (asks the phone for that deck's latest cards).
- Only deck names and counts leave the apps; nothing grades cards or writes to AnkiDroid. Function
  descriptions come from KDoc, so those KDocs follow the AppFunctions skill's style rather than the
  sparse-comment rule.
- Alpha drift: alpha13 renamed `@AppFunction` to `@AppFunctionDeclaration` and alpha12 removed
  `AppFunctionContext`; Google's guide and skill still show the old names.
- Verify with `adb shell cmd app_function list-app-functions --package fi.nikosavola.ankidroid.wearos` and
  `execute-app-function --function '<BaseClass FQN>#<fn>' --parameters '{...}' --brief-yaml`. API
  36.0 images have the service but no shell command; the Wear OS 7 (API 37) emulator has both. A
  returned `PendingIntent` is launched with
  `--pending-intent-path property/androidAppfunctionsReturnValue`.
- `ksp*Kotlin` tasks depend on `:generateBindings` like the compile tasks, or a clean build fails.

---

## 11. Published library (`:ankidroid-provider`)
The generic AnkiDroid ContentProvider client lives in the Android library module
`ankidroid-provider` (package `fi.nikosavola.ankidroid.provider`): `AnkiDroidContract` (URIs and
columns), `AnkiDroidProvider` (decks, counts, options, the schedule queue, card reads and searches,
`prop:pos` new-card windows, and `writeAnswers`, the confirmed write-back procedure),
`DeckSelector` (keeps `(did:...)` last). `:phone`'s `AnkiDroidBridge` is a facade that maps it to
the `Wearos*` types and owns the pool, the new-limit inference and the `read_only` gate.
- No Rust, KSP or `../bindings` in the library; it must build on its own (JitPack builds only it).
- `.github/workflows/publish.yml` publishes it to GitHub Packages on `v*` tags (tests first; the
  registry never overwrites a version). `jitpack.yml` builds the same module.
- Check a release locally with `./gradlew :ankidroid-provider:publishToMavenLocal
  -PreleaseVersion=0.0.1-test` and inspect the POM and AAR; delete it from `~/.m2` afterwards.

---

## 12. Watch surfaces and app quality
- **Surfaces:** a tile (`AnkiTileService`, Material3TileService), a complication (`AnkiComplicationService`: short text, long text, ranged progress) and a Wear OS 7 widget (`DueCardsWidget`, alpha Glance Wear + Remote Compose; small and large containers). `WearSurfaceUpdater` updates all three after a phone sync (`refresh`, which waits for the widget push since the listener's process can die once it returns) and when the app stops (`requestRefresh`; grades change the counts). The carousel picker only lists `BIND_TILE_PROVIDER` services, so the widget service declares that action too and is enabled from API 37 only (`values-v37/bools.xml`), keeping older watches to the one tile. The widget API has no timed refresh, so it shows absolute times ("at 14:35"), never countdowns. Add it on an emulator with `adb shell am broadcast -a com.google.android.wearable.app.DEBUG_SURFACE --es operation add-tile --ecn component fi.nikosavola.ankidroid.wearos/fi.nikosavola.ankidroid.wearos.wear.DueCardsWidgetService --ei type 1` (2 for small).
- **Emulator decks without a phone:** emulators can't pair without the Play Store Wear OS app, and GMS won't deliver a data item back to the node that wrote it, so seed demo decks with the instrumentation helper `DemoDeckSeeder` (`./gradlew :wear:installDebug :wear:installDebugAndroidTest`, then `adb shell am instrument -w -e seed true -e class fi.nikosavola.ankidroid.wearos.wear.DemoDeckSeeder fi.nikosavola.ankidroid.wearos.test/androidx.test.runner.AndroidJUnitRunner`). It does nothing without `-e seed true`; never pass that to a real watch, it replaces the deck list.
- **Ongoing review:** `ReviewOngoing` posts an OngoingActivity notification while a session has cards left (no foreground service; the session is foreground UI only), so the watch face links back into the review.
- **State:** the screen stack and the session live in `WatchViewModel`; after process death the session is rebuilt from the pool, buffered answers are never lost.
- **Theme:** always black backgrounds (Wear guideline), and phone-rendered card images are white-on-transparent, so there is no light theme.
- **Phone:** one lazy list on compact windows (Sync is a FAB, also in the overflow menu); `SupportingPaneScaffold` from 600dp. The pane scaffold's auto-focus is off (`shouldAutoFocusCurrentDestination = false`, set through the primary constructor because `copy()` resets it), or the search field grabs focus and the keyboard opens on every launch.
