#!/usr/bin/env bash
# Regenerate docs/screenshots from a booted Wear OS emulator.
#
# An emulator cannot pair with a phone (GMS will not deliver a data item back to the node that
# wrote it), so the decks come from DemoDeckSeeder and the walk below drives the real app with adb
# input at fixed coordinates on the 454x454 round screen. Rerun after UI changes:
# `just screenshots [serial]`.
set -euo pipefail

serial="${1:-emulator-5554}"
sdk="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
adb="$sdk/platform-tools/adb"
pkg=fi.nikosavola.ankidroid.wearos
activity="$pkg/$pkg.wear.MainActivity"
root="$(cd "$(dirname "$0")/.." && pwd)"
out="$root/docs/screenshots"

command -v cwebp >/dev/null || { echo "error: install cwebp (brew install webp)" >&2; exit 1; }

# Lossless: these are flat UI captures, and lossy webp smears the small grade-pill text.
shot() {
  local png
  png="$(mktemp -t ankiwearos-shot).png"
  "$adb" -s "$serial" exec-out screencap -p > "$png"
  cwebp -quiet -lossless -z 9 -metadata none "$png" -o "$out/$1.webp"
  rm -f "$png"
}
tap() { "$adb" -s "$serial" shell input tap "$1" "$2"; sleep 1; }
# Rows sit on a curved list; one swipe from the top lands the same decks under the same taps.
swipe_up() { "$adb" -s "$serial" shell input swipe 227 400 227 100 300; sleep 1; }

mkdir -p "$out"
"$adb" -s "$serial" wait-for-device
until [ "$("$adb" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; do
  sleep 2
done

# Fresh state every run, so the captures always show the same decks, counts and session.
"$adb" -s "$serial" shell pm clear "$pkg" >/dev/null
"$adb" -s "$serial" install -r "$root/wear/build/outputs/apk/debug/wear-debug.apk" >/dev/null
"$adb" -s "$serial" install -r "$root/wear/build/outputs/apk/androidTest/debug/wear-debug-androidTest.apk" >/dev/null
# Granted up front, or the review's ongoing-activity prompt covers the deck screen.
"$adb" -s "$serial" shell pm grant "$pkg" android.permission.POST_NOTIFICATIONS
"$adb" -s "$serial" shell am instrument -w -e seed true -e class "$pkg.wear.DemoDeckSeeder" \
  "$pkg.test/androidx.test.runner.AndroidJUnitRunner" >/dev/null

"$adb" -s "$serial" shell am start -n "$activity" >/dev/null
sleep 3
swipe_up
shot decks

# Settings first. Relaunching rather than pressing back keeps the list at its top so the taps
# below always land on the same rows.
tap 227 320
sleep 2
shot settings

"$adb" -s "$serial" shell am force-stop "$pkg"
"$adb" -s "$serial" shell am start -n "$activity" >/dev/null
sleep 3
swipe_up

# Spanish has four plain cards, so one session covers the review screens.
tap 227 190
sleep 2
tap 227 395
sleep 2
shot question
tap 227 227
sleep 2
shot answer

# Good is the second pill from the right; the grade strip maps x position to ease.
tap 300 400
for _ in 1 2 3; do
  tap 227 227
  tap 300 400
done
sleep 2
shot finished
