# Write-back debugging handoff (watch grades -> AnkiDroid scheduling)

## RESOLVED (root-caused + fixed + verified on the redroid emulator)

Root cause: a bare `ContentResolver.update()` on `ReviewInfo`/`schedule` **silently no-ops** on
AnkiDroid 2.24.0's rslib/v3 backend. It returns rows>0, but `col.sched.answerCard` throws internally
and the provider swallows it, so nothing persists. The ContentValues keys were correct all along
(`note_id`, `ord`=ReviewInfo.CARD_ORD, `answer_ease`, `time_taken`).

Fix: `AnkiDroidBridge.writeAnswersBack` now **queries the schedule first (`primeScheduler`) to reset
the scheduler / rebuild the review queue, then updates to answer** — mirroring AnkiDroid's own passing
`ContentProviderTest`, which fetches from the scheduler before answering.

Verified confound-free on redroid (Android 13, adb root reading collection.anki2 + the live provider):
- Bare update: card stays due, `reps=0`, no `revlog` row.
- Query-then-update: the answer persists — card advances (new -> learning/review), `reps` increments,
  a `revlog` row is written, and it leaves the due queue as expected.

Known constraint (also emulator-verified): the v3 backend only answers the card the scheduler
**currently presents**. Answering the current card persists; answering a different (non-current) card
no-ops. So write-back must apply answers in the scheduler's presentation order. This matches the
normal flow (the watch batch is fetched in that order, graded and buffered in order, and replayed in
order), but out-of-order answers or a second answer for a card relearned within one watch session may
not all land. Follow-up: reconcile per-answer against the scheduler's current card (match by note_id/
ord, or re-fetch), tracked in TODO.md.

Everything below is the original handoff, kept for context.

---

Status as of this session. The goal: grades made on the watch should update AnkiDroid's real
schedule. On-device testing shows they currently **do not**. This documents exactly what was found,
what was ruled out, and how to finish the investigation on an emulator where the collection is
readable.

## TL;DR

- **Confirmed broken** on **AnkiDroid 2.24.0** (modern libanki/Rust backend): a grade synced from
  the watch is *not* recorded in AnkiDroid's scheduler.
- The phone logs `Write-back: 1 applied` (`ContentResolver.update` returned `rowsUpdated > 0`), but
  the card's schedule does not change.
- Root cause is **not pinned**. It contradicts AnkiDroid's own source (which says the bare update
  should call `answerCard` and persist). It could not be resolved from outside AnkiDroid because the
  collection is opaque here (release build = not debuggable, scoped storage, DB unreadable).
- **Next step: reproduce on an emulator with a *debuggable* AnkiDroid** so `collection.anki2` (the
  `cards` table: `queue`, `due`, `ivl`, `reps`) can be read directly before/after a grade.

## The code under test

`phone/src/main/java/com/ichi2/anki/wearos/phone/AnkiDroidBridge.kt`:
- `writeAnswersBack(answers, readOnly)` -> for each answer:
  `ContentResolver.update(SCHEDULE_URI, values, null, null)` where `values` has
  `NOTE_ID`, `CARD_ORD`, `EASE`, `TIME_TAKEN`. `SCHEDULE_URI` = `FlashCardsContract.ReviewInfo.CONTENT_URI`.
  `rowsUpdated > 0` -> counted "applied"; `== 0` -> "dropped (no match)"; exception -> kept for retry.
- `fetchDueBatch(deckId, limit)` -> queries the same `SCHEDULE_URI` with selection
  `"limit=?,deckID=?"` and reads `COL_NOTE_ID` (a real note id, not the card id) + `COL_CARD_ORD`.

`WearSyncService.processAnswers` calls `writeAnswersBack` then broadcasts `/clear_answers`.

## What was observed (confound-free method)

Because AnkiDroid caches the collection in-process and its deck-browser counts can be stale, the
only reliable read is the **due count via `fetchDueBatch`** (same ContentProvider, same backend as
the write), combined with a **force-stop of AnkiDroid to force a fresh DB reload**.

Procedure used:
1. Created a test note in a throwaway deck, made it a **due learning card** (rate Again once), so
   `fetchDueBatch(deck)` returns exactly 1 due card. (Baseline: phone log `Pushed deck ... with 1 cards`.)
2. Reviewed it on the watch and graded **Easy** (which should graduate a learning card to ~4 days,
   removing it from "due"). Phone logged `Write-back: 1 applied`.
3. Re-ran `fetchDueBatch` -> **still 1 due card** (an applied Easy would make it 0).
4. `am force-stop com.ichi2.anki` (drops the in-memory collection), then `fetchDueBatch` again ->
   **still 1 due card**. On 2.24 the Rust backend commits transactionally, so a persisted answer
   would survive the reload. It did not.

Reproduced 3x. Conclusion: the review is not being persisted, despite `rowsUpdated > 0`.

## Confounds that wasted time (avoid these)

- **The Anki MCP / AnkiConnect used for setup talks to DESKTOP Anki on the laptop**, a *separate*
  collection from AnkiDroid, reconciled only via AnkiWeb. Never verify AnkiDroid writes by querying
  the desktop collection. (Desktop is fine for *setup* - create a card, then sync desktop->AnkiWeb
  and pull to AnkiDroid.)
- **AnkiDroid deck-browser counts and ContentProvider query results are cached in-process.** A read
  right after a write can return stale data. Force-stop AnkiDroid to get a fresh read.
- **AnkiDroid may sync-on-open** and overwrite local ContentProvider writes with the AnkiWeb state.
  During this session it appeared to be manual-sync only, but verify and disable auto-sync on the
  emulator.
- **New cards are deferred by the daily new-card limit** ("more new cards available, but the daily
  limit has been reached"), so a fresh new card is not "due" and cannot be answered via the
  scheduler ("not at top of queue" from AnkiConnect). Use a card made due (rate Again) or raise the
  deck's new/day limit.

## Contradicting evidence to resolve

DeepWiki on `ankidroid/Anki-Android` (current source) says the SCHEDULE `update()` path is:
`getCard(noteId, cardOrd, col)` then `answerCard(col, card, ease, timeTaken)` ->
`col.sched.answerCard(...)`, and `updated++` runs only if no exception. Its `ContentProviderTest.
testAnswerCard()` does a bare update (no prior query) and asserts the card's queue changed. So per
the source it should work. Either 2.24.0 differs, or there is a precondition not visible from
outside. Since `updated++` ran (returned 1) with no exception, `answerCard` "completed" but had no
effect - suggesting the libanki backend no-ops answering a card that is not the scheduler's current
card.

## Candidate fixes to try on the emulator (in order)

1. **Query-then-answer.** Before the update, `query(SCHEDULE_URI, null, "limit=1,deckID=<deck>", ...)`
   for the answer's deck to make the card the scheduler's current card, then update. (WearosAnswer
   has no deck id today - would need to carry it, or resolve the card's deck first.)
2. **note_id vs card_id.** Verify with a *real* card where `noteId != cardId` (our test card had
   them equal, so a mix-up would be invisible). fetchDueBatch reads `COL_NOTE_ID`, which looks
   correct, but confirm end-to-end.
3. **Newer answer API.** Check whether 2.24 expects a different ContentProvider contract or the
   `com.ichi2.anki.api` AddContentApi answer path.
4. If none work, it may be an AnkiDroid 2.24 ContentProvider bug on the Rust backend - test the same
   bare update from a tiny standalone app to isolate from our code, and file upstream if confirmed.

## Definitive test harness for the emulator

With a **debuggable** AnkiDroid (F-Droid debug build or self-built), `run-as com.ichi2.anki` works,
so you can read the collection DB directly:
- Find `collection.anki2` under the app's files dir; `sqlite3 collection.anki2
  "SELECT id,queue,due,ivl,reps,lapses FROM cards WHERE id=<cardId>"` before and after a grade.
- A working write-back changes `queue`/`due`/`ivl`/`reps`. That is the ground truth - no sync, no
  cache, no desktop.
- Keep AnkiDroid auto-sync OFF during the test.

## Related bugs found (also in TODO.md)

- **clear_answers handshake double-apply:** the `WearableListenerService` scope is cancelled
  (`Job was cancelled`) before the `/clear_answers` confirmation is sent, so the watch re-sends and
  the phone applies the same answers again (timing-dependent, ~50% seen). Make `writeAnswersBack`
  idempotent (dedupe by answer id) and/or send the handshake from a scope that outlives the service
  callback (`goAsync`/WorkManager). Independent of the persistence bug; fix regardless.

## Environment

- Phone: Pixel 8 Pro, `com.ichi2.anki.wearos` (companion), `com.ichi2.anki` 2.24.0.
- Watch: Galaxy Watch SM-L330 (arm64, 480x480, adb coords map 1:1).
- Test mode toggles live in the companion Settings dialog: **Force Mock Mode** (synth cards) and
  **Read-only mode** (skips write-back). Both must be OFF to exercise real write-back; turn them
  back ON when done.

## Local Android emulator (redroid) — available on this VM

Headless Android 13 (x86_64) runs here via Docker + redroid, sharing the host kernel (no KVM/AVD).
This unblocks on-device verification (write-back, phone UI) without a physical device.

One-time host prereq (binder), doable from a privileged container (no root login needed here):

```bash
docker run --rm --privileged -v /lib/modules:/lib/modules:ro alpine \
  sh -c 'apk add -q kmod && modprobe binder_linux devices="binder,hwbinder,vndbinder"'
```

Run / connect:

```bash
docker run -itd --privileged --name redroid -v "$HOME/redroid-data:/data" -p 5555:5555 \
  redroid/redroid:13.0.0-latest androidboot.redroid_gpu_mode=guest
adb connect localhost:5555          # wait for: getprop sys.boot_completed == 1
adb -s localhost:5555 install -r phone/build/outputs/apk/debug/phone-debug.apk
adb -s localhost:5555 exec-out screencap -p > shot.png   # visual verification
```

`docker start redroid` to resume (data persists in ~/redroid-data). Notes: the image is AOSP with
**no Google Play Services**, so the Wearable Data Layer paths won't work — it's for the AnkiDroid
ContentProvider write-back and phone-UI rendering, not the phone<->watch transfer. redroid is phone
Android, not Wear OS (watch UI still needs a Wear device/AVD). The bluetooth HAL SIGABRTs in logcat
are a benign redroid quirk. To exercise write-back: install an x86_64 AnkiDroid build, create a
collection, grant the companion the READ_WRITE_DATABASE permission, then follow the steps above.
