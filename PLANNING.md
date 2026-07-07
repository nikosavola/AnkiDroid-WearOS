# AnkiDroid WearOS Companion — Planning Document

Status: draft / pre-implementation. Blank-slate project. Not affiliated with or forked
from the abandoned `wlky/AnkiDroid-Wear`; that project is referenced only for lessons.

## 1. Goal

A Wear OS app to review AnkiDroid flashcards from the wrist: see what's due, read the
front, reveal the answer, grade it (Again / Hard / Good / Easy), and have the grade land
back in the real AnkiDroid collection with correct scheduling.

Constraint from the outset: put as much logic as possible in **Rust**, with a shared
core crate driving both the phone-side bridge and the watch UI. Kotlin is used only where
the platform forces it (UI, IPC, ContentProvider, Data Layer). See §6 for the honest
Rust/Kotlin boundary.

## 2. Prior art and lessons

### `wlky/AnkiDroid-Wear` (abandoned)
- Phone-companion, three Gradle modules (`mobile`, `wear`, `sharedvalues`). Watch held no
  data; it requested a card, showed Q/A, captured the grade, sent it back.
- Phone-watch link used the legacy `GoogleApiClient` + `MessageApi`/`DataApi` (GMS,
  now deprecated in favour of `MessageClient`/`DataClient`).
- Data access via the official `com.ichi2.anki.FlashCardsContract` ContentProvider
  (not by reading `collection.anki2` directly). Scheduling fully delegated to AnkiDroid:
  answers written to `ReviewInfo.CONTENT_URI` with `EASE` + `TIME_TAKEN`.
- 100% Java, Android Wear 1.x/2.x era. First commit 2015, last release `1.0alpha31`
  (2017), brief revival 2021-2022, dead since. ~20 open issues.
- Documented failure modes we must design against:
  - Install path (phone APK auto-pushing the watch APK) broke on modern watches
    (Galaxy Watch 4, Pixel Watch, Ticwatch). No standalone watch APK.
  - Answer gestures (drag-up for Good/Easy) unreliable on round screens.
  - Media (images/sounds) never worked cleanly; retrofitted via Storage Access Framework
    on the phone side and broke on newer devices.
  - Fill-in-the-blank / JS / LaTeX cards render poorly.
  - No AnkiWeb sync; reviews only hit the local collection until the user opened AnkiDroid.
  - Dependence on deprecated GMS wearable APIs.

### `Abhishek14104/Anki_Companion`
- A 2026 Kotlin MVP reimplementation of the same idea. Worth a read for a modern baseline,
  but out of scope as a dependency.

Takeaways: ship a real standalone watch build; grade with buttons + rotary, not gestures;
scope card content tightly for v1; make sync-back explicit; don't build on deprecated APIs.

## 3. How AnkiDroid exposes data

AnkiDroid ships an API library, `com.ichi2.anki:api`, gated by a single runtime
permission `com.ichi2.anki.permission.READ_WRITE_DATABASE` (auto-merged into the manifest
via the Gradle dependency, requested at runtime on Android 6+).

Two layers:

1. `AddContentApi` — high-level, write/create only (add notes, models, decks, media).
   No reviewing. Not what we need.
2. `FlashCardsContract` ContentProvider (authority `com.ichi2.anki.flashcards`) — the real
   surface. Relevant URIs:
   - `Note` / `Model` / `CardTemplate` — read note types, fields, templates; query notes
     with Anki search syntax.
   - `Card` — read cards incl. scheduling metadata columns (`INTERVAL`, `RAW_DUE`,
     `RAW_SM2_FACTOR`, and FSRS `FSRS_STABILITY` / `FSRS_DIFFICULTY` / `FSRS_DECAY`).
   - `ReviewInfo` / schedule (`content://com.ichi2.anki.flashcards/schedule`) — **the review
     interface**:
     - Query = the due study queue (`deckID`, `limit` args). Columns: `NOTE_ID`,
       `CARD_ORD`, `BUTTON_COUNT`, `NEXT_REVIEW_TIMES` (per-ease scheduled times),
       `MEDIA_FILES`.
     - Update with `NOTE_ID`, `CARD_ORD`, `EASE` (`Ease.EASE_1..EASE_4`), `TIME_TAKEN`
       = answer the card. Internally calls the real scheduler (`col.sched.answerCard`).
     - Update with `BURY=1` / `SUSPEND=1` = bury/suspend.

Key consequence: **the full review loop is available through supported APIs.** We do not
need to reimplement the scheduler, read the raw DB, or ship a dev build of AnkiDroid. We do
depend on AnkiDroid being installed and reachable on the phone — this API cannot be
serviced fully standalone on the watch. Gotchas: `EASE`/`TIME_TAKEN` vs `BURY` vs `SUSPEND`
are mutually exclusive per update; ease semantics differ under SM-2 vs FSRS; media is not
served by the provider (paths only).

## 4. What's reusable from Anki's Rust ecosystem

- Anki's core is already Rust: the `anki` crate (a.k.a. `rslib`) in `ankitects/anki` holds
  the v3 scheduler, FSRS integration, collection SQLite access, and deck/card/note
  management. `.anki2` is a plain SQLite DB it owns.
- FSRS: the `fsrs` crate (`open-spaced-repetition/fsrs-rs`, uses the Burn ML framework —
  training + optimization + simulation) and a lighter `rs-fsrs` (scheduler only, no
  optimizer). Both standalone Rust.
- AnkiDroid binds to `rslib` via `ankidroid/Anki-Android-Backend` (`net.ankiweb.rsdroid`):
  a JNI + protobuf bridge, Rust compiled with `cargo-ndk` into an `.aar`. The backend is
  defined as protobuf services in `proto/anki/*.proto` (e.g. `scheduler.proto`:
  `GetQueuedCards`, `AnswerCard`, `ComputeFsrsParams`).

Implication for us: for the companion path we do **not** embed `rslib` (AnkiDroid already
runs it on the other side of the ContentProvider). We can reuse `rs-fsrs` if/when we want
on-watch interval previews or an eventual standalone mode (§5, Phase 3). The
`Anki-Android-Backend` build scaffolding is a good reference for our own cargo-ndk setup.

## 5. Architecture decision

Three candidate shapes:

| Option | Watch depends on | Scheduling source of truth | Rust footprint | Verdict |
|---|---|---|---|---|
| A. Companion (thin watch) | Phone + AnkiDroid | AnkiDroid via ContentProvider | Small | Baseline, proven |
| B. Companion + shared Rust core | Phone + AnkiDroid | AnkiDroid | Medium (session/cache/protocol in Rust) | **Recommended** |
| C. Standalone (embed `rslib`) | Nothing (own `.anki2` + AnkiWeb sync) | On-device Rust scheduler | Large | Ambitious, later |

Decision (settled): **Option B.** Ship a companion app first; do not over-invest in
Option C readiness. AnkiDroid stays the scheduling source of truth (it already runs the
correct Rust scheduler), and we honour the "logic in Rust" goal by putting the
review-session state machine, the local due-card cache, the sync protocol/serialization,
and answer buffering in a Rust crate driving the watch app (see §13 on the phone side).

Accepted trade-off of B: the genuinely hard core logic — the SM-2 / FSRS scheduler — is
Rust, but it is *AnkiDroid's* Rust (`rslib`), on the far side of the ContentProvider IPC,
not code this project owns. What B leaves for our own crate (session state, cache,
serialization, buffering) is comparatively thin. We accept this to get correct scheduling
for free and a shippable v1; owning the scheduler would be Option C (embed `rslib`/
`rs-fsrs` on-device) with collection-ownership + AnkiWeb-sync cost, which we are choosing
*not* to take on now. Keep the crate boundaries clean so C stays possible, but don't build
scaffolding for it speculatively.

Do not ask companion-vs-standalone as a product question now — "companion" is the stated
intent and the API supports it. Standalone (C) becomes worth it only if we want reviewing
with the phone off entirely; it carries AnkiWeb sync + collection ownership complexity and
is deferred.

## 6. The Rust / Kotlin boundary (honest scope)

Rust owns (shared `core` crate, exposed to Kotlin via **UniFFI**):
- Review-session state machine (queue position, front/back reveal state, elapsed timing).
- Local SQLite cache of the synced due batch and buffered answers (`rusqlite`, `bundled`).
- Answer buffering + reconciliation logic (what's been graded, what still needs to reach
  the phone, idempotency/dedup keys).
- Sync protocol: message/payload schemas, (de)serialization, versioning.
- Card-content sanitization for the watch (strip/normalize HTML to a display model).
- Interval-preview formatting from `NEXT_REVIEW_TIMES`.
- Optional later: `rs-fsrs` for on-watch interval prediction / standalone scheduling.

Kotlin owns (platform-forced):
- All UI — Jetpack Compose for Wear OS (Material 3).
- Phone↔watch transport — Wearable Data Layer API (`DataClient`, `MessageClient`).
- The AnkiDroid bridge on the phone — `ContentResolver` + `FlashCardsContract`.
- Permission requests, lifecycle, `WearableListenerService`, notifications, TTS/STT.
- Card rendering when we go beyond plain text — a `WebView` (Anki cards are HTML/CSS/JS).

Rust does not touch: UI, IPC, ContentProvider, WebView. Trying to push those into Rust
would fight the platform for no gain.

## 7. Data flow

```
AnkiDroid (phone)                Phone companion (Kotlin + Rust core)         Watch (Kotlin + Rust core)
  collection.anki2                                                              
  + rslib scheduler                                                             
        |  FlashCardsContract IPC                                               
        |  (schedule/ReviewInfo,                                                
        |   Card, Note, Model)                                                  
        v                                                                       
   [ContentResolver] <----> [Rust core: build due batch, cache] --DataClient--> [Rust core: session,
        ^                    [answer reconciliation]              (DataItem +     cache, state]
        |  answer: update                                          Asset blob)         |
        |  ReviewInfo(EASE,     <---- DataClient (buffered answers) -----------  grade captured
        |  TIME_TAKEN)                                                           (button/rotary)
```

- Sync down (phone → watch): companion queries the schedule URI for a bounded batch of due
  cards (limit N, chosen deck), Rust builds a compact display model + interval previews,
  serialized and pushed as a `DataClient` `DataItem` (large batches as a compressed
  `Asset`). `DataClient` persists and syncs opportunistically, surviving the offline-first
  requirement and the ~4 KB/s Bluetooth reality.
- Review (on watch): Rust session state machine drives reveal → grade; each grade is
  written to the on-watch SQLite buffer with an idempotency key and elapsed `TIME_TAKEN`.
- Sync back (watch → phone): buffered answers pushed via `DataClient` (not `MessageClient`,
  which has no retry and fails if the peer is gone). Companion drains the buffer and writes
  each to `ReviewInfo.CONTENT_URI`; reconciliation dedups on the idempotency key.
- Sync to AnkiWeb: out of scope for the app; the user syncs by opening AnkiDroid (or its
  background sync). Surface a clear "reviews saved to AnkiDroid, sync to AnkiWeb there"
  state so we don't repeat the silent-no-sync confusion of the old project.

## 8. Tech stack and tooling

UI / Wear:
- Kotlin + Jetpack Compose for Wear OS, Material 3 (`androidx.wear.compose:compose-material3`),
  targeting Wear OS 6 (Android 16, Material 3 Expressive).
- `TransformingLazyColumn` (M3) for lists; `AppScaffold` + `ScreenScaffold` for
  time-text / scroll-indicator / rotary wiring. Horologist only for specific helpers.
- `standalone=true` manifest flag; ship a real standalone Wear AAB on Play (App Bundle,
  per-ABI splits).

Transport:
- Wearable Data Layer API (`com.google.android.gms.wearable`, `GoogleApi` style).
  `DataClient` for both directions (offline-first). `CapabilityClient` to detect the phone
  companion; `MessageClient` only for live, connected niceties.

Rust:
- Shared `core` crate. Bindings via **UniFFI** (proc-macro attributes, `uniffi-bindgen`
  → Kotlin via a Gradle `Exec` task; generated Kotlin uses JNA — add
  `net.java.dev.jna:jna` `@aar` + `kotlinx-coroutines-core`; Rust `async fn` → Kotlin
  `suspend`). UniFFI chosen over raw JNI (boilerplate/safety) and Diplomat/flapigen
  (less mature Kotlin) for a coarse-grained call surface where JNA overhead is negligible.
- Build with `cargo-ndk`. Target `aarch64-linux-android` (arm64-v8a) as baseline; add
  `armv7-linux-androideabi` only for legacy 32-bit watches; `x86_64` for the emulator.
- **16 KB page-size compliance is mandatory** (Android 15+, since Nov 2025): NDK r28+,
  AGP 8.5.1+, no hardcoded page size — call `getpagesize()`. Applies to Wear OS 6.
- Binary size discipline (storage + slow BT transfer): `--release`, `strip`,
  `panic = "abort"`, `opt-level = "z"`, LTO, prune features. `rusqlite` `bundled` adds
  ~1 MB/ABI — ship only needed ABIs.
- SQLite: `rusqlite` with `bundled` (compiles + statically links SQLite via `cc`). Do not
  link the system `libsqlite3` on Android; it's fragile.

## 9. Interaction model (watch)

- Grade with large buttons and/or rotary selection, never drag-up gestures (the old
  project's #1 UX failure on round screens). Don't fight system swipe-to-dismiss.
- Reveal flow: front → tap/crown to reveal → four grade buttons with interval previews
  from `NEXT_REVIEW_TIMES`.
- Rotary input via `Modifier.rotaryScrollable` (auto inside scaffolded lists) for browsing
  the queue and picking a grade.
- Optional eyes-free mode: TTS reads front/answer, STT or rotary for grading. Per-device
  voice availability and battery caveats apply; keep it opt-in.
- No free-text entry in v1 (keyboard on a watch is painful).

## 10. Scope for v1 (and non-goals)

In scope:
- Text-only Basic (front/back) and reversed cards. Deck selection. Bounded due batch.
- Reveal + 4-button grading with interval previews. Offline review, buffered sync-back.
- Standalone-flagged watch build + phone companion; `CapabilityClient` pairing check.

Explicit non-goals for v1 (call out in-app where a card can't render):
- Media (images/audio), MathJax/LaTeX, cloze, JS-heavy templates, typed-answer cards.
  These need the `WebView` renderer + media transfer (Phase 2).
- On-device scheduling / AnkiWeb sync / collection ownership (Phase 3, Option C).
- iOS-paired nuances beyond what standalone gives us.

## 11. Phased roadmap

- Phase 0 — Skeleton: repo layout (§12), cargo-ndk + UniFFI build wired into Gradle, a
  trivial Rust call from the watch app, phone↔watch `DataClient` round-trip, permission
  flow against a real AnkiDroid install. De-risk the toolchain before features.
- Phase 1 — MVP (Option B): companion queries schedule URI, Rust core builds batch +
  session, watch reviews text cards, answers reconcile back to `ReviewInfo`. AnkiWeb sync
  is manual. Ship internally.
- Phase 2 — Content + polish: `WebView` card rendering, media transfer via `Asset`/
  `ChannelClient`, TTS/STT, Tiles ("cards due today") + complications, deck stats glance.
- Phase 3 — Standalone (Option C): not planned. Recorded only as a future possibility if
  phone-free review ever becomes a requirement — would embed `rslib`/`rs-fsrs` via a
  JNI+protobuf `.aar` (mirroring `Anki-Android-Backend`) to own a `.anki2` on-watch and
  sync to AnkiWeb. Kept reachable by clean crate boundaries, not actively built toward.

## 12. Proposed repo layout

```
/core            # Rust: core crate (session, cache, protocol, sanitize). UniFFI-exported.
                 # Watch-side by default; phone uses a slim Kotlin bridge (see §13).
  /src
  Cargo.toml
/bindings        # generated Kotlin (UniFFI) — build output, gitignored
/phone           # Kotlin: companion app. ContentProvider bridge + DataClient + core.
/wear            # Kotlin: Wear OS app. Compose M3 UI + DataClient + core.
/shared-proto    # message/payload schemas (source of truth in Rust; mirror if needed)
gradle / build   # cargo-ndk + uniffi-bindgen wired as Gradle tasks
```

## 13. Risks and open questions

- ContentProvider fidelity: confirm `NEXT_REVIEW_TIMES` / `BUTTON_COUNT` behave the same
  under SM-2 and FSRS collections, and that `TIME_TAKEN` measured on-watch is acceptable.
- Ease-count variance: some cards have 3 buttons, some 4 — UI must adapt from `BUTTON_COUNT`.
- Bluetooth throughput (~4 KB/s) bounds batch size; tune N and compression. Test with large
  decks and an off-body watch.
- UniFFI + JNA on Wear: validate binary size and cold-start on a low-end watch early
  (Phase 0). Fall back to raw JNI for the core surface only if size/latency disappoints.
- 16 KB alignment must be verified in CI on the produced `.so`s, not assumed.
- AnkiDroid API stability: it's a supported but evolving surface (some raw Card fields
  deliberately withheld per upstream discussion). Pin behavior with instrumented tests
  against a known AnkiDroid version.
- Distribution: avoid the old auto-push install trap — separate, directly installable Wear
  AAB.
- Rust core is thin on the phone side: the phone has live `ContentResolver` access; the
  cache and answer buffer live on the *watch*. Forcing the crate onto both apps risks
  symmetry-for-its-own-sake — on the phone it mostly passes data through. Working
  assumption: Rust core lives watch-side, with a slim Kotlin phone bridge; revisit only if
  the phone gains logic worth sharing.

## 14. References

- AnkiDroid API: <https://github.com/ankidroid/Anki-Android/wiki/AnkiDroid-API>,
  `FlashCardsContract` in `ankidroid/Anki-Android`.
- Anki backend: `ankitects/anki` (`rslib`, `proto/anki/*.proto`),
  `ankidroid/Anki-Android-Backend` (`net.ankiweb.rsdroid`).
- FSRS: `open-spaced-repetition/fsrs-rs` (`fsrs`), `open-spaced-repetition/rs-fsrs`.
- Prior art: `wlky/AnkiDroid-Wear`, `Abhishek14104/Anki_Companion`.
- Wear OS: Compose M3, Data Layer client types, standalone apps, rotary input, power,
  16 KB page sizes (developer.android.com).
- Rust tooling: `bbqsrc/cargo-ndk`, `mozilla/uniffi-rs`, `rusqlite/rusqlite`.
