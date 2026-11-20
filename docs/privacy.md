# Wear for AnkiDroid privacy policy

Effective 20 November 2026.

Wear for AnkiDroid is two apps, a phone companion and a watch app. This policy describes what they
do with your information.

## What the apps do with your information

The phone app reads your decks, due cards, card text and review options from AnkiDroid. It uses
AnkiDroid's ContentProvider API, and only after you grant AnkiDroid's database permission. It sends
the due cards to the watch over Google Play services' Wearable Data Layer.

The watch app stores the cards it receives in a local database, shows them, and records the grades
you give. Grades wait in a local queue when the phone is not reachable, then go back over the same
Data Layer. The phone app writes them into your AnkiDroid collection, which stays the authority for
scheduling.

Card text can contain personal notes. It is sent between your phone and your watch only, through
Google Play services, and the developer does not receive it.

## What the apps do not do

- They do not send your data to the developer.
- They have no accounts, no sign-in and no servers of their own.
- They do not use analytics, advertising, crash reporting or any other telemetry.
- They do not read or write Health Connect or any other health record store.
- They do not work without AnkiDroid. If AnkiDroid is missing, or its API is disabled, the apps do
  nothing.

## Google Play services

The phone and watch apps use Google Play services' Wearable Data Layer to talk to each other.
Google handles that transport under its own terms and privacy policy. No server run by the
developer is involved.

## Data retention

- On the watch, grades waiting to be sent are kept until the phone confirms they were written into
  AnkiDroid. This can be several days if the phone stays away.
- On the watch, the card pool stays until a newer sync replaces it, or until you clear the app's
  data or uninstall the app.
- On the phone, the deck selection and sync preferences stay until you change them, clear the app's
  data, or uninstall the app.
- The developer keeps no data.

## Data deletion

The developer holds no data about you, so there is nothing to request or delete from the developer,
and there is no deletion request process.

Everything the apps store is on your devices, and you can remove it yourself:

- Clear the watch app's storage in the watch's settings, or uninstall the app, to remove the card
  pool and any grades still waiting to be sent. Grades that were never sent cannot be recovered.
- Clear the phone app's storage, or uninstall it, to remove its preferences. Your AnkiDroid
  collection is not touched by this. Change or remove cards and reviews through AnkiDroid itself.

## Permissions and why they are asked for

- **AnkiDroid database access** (`com.ichi2.anki.permission.READ_WRITE_DATABASE`). Reads decks and
  cards from AnkiDroid and writes grades back. Required. AnkiDroid shows the grant prompt, and you
  can revoke the permission in AnkiDroid's settings.
- **Notifications**. On the phone, shows sync progress. On the watch, shows the ongoing review.
  Optional. Refusing it hides those notifications only.
- **Foreground service (data sync)**. Lets the phone finish a sync while you use other apps.
  Required for background sync.

## Children

The apps are not directed at children and collect nothing from anyone.

## Changes

If this policy changes, the date at the top changes with it. The current version stays at the same
public address.

## Contact

Open an issue at <https://github.com/nikosavola/Wear-for-AnkiDroid/issues>.

## Trademarks

Anki and the Anki logo are trademarks of Anki Software, LLC. AnkiDroid is a trademark of the
AnkiDroid Open Source Team. Wear for AnkiDroid is an unofficial third-party companion and is not
affiliated with or endorsed by either.
