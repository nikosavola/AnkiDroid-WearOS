# justfile for AnkiDroid-WearOS. Run `just` or `just --list` to see all recipes.
#
# Every gradle recipe caps parallelism via max_workers (default 2, override with JUST_MAX_WORKERS):
# a workstation preference, not a project requirement, so it lives here rather than in
# gradle.properties, which is committed and shared across machines.

max_workers := env('JUST_MAX_WORKERS', '2')
sdk := env('ANDROID_HOME', env('ANDROID_SDK_ROOT', if os() == 'macos' { env('HOME') + '/Library/Android/sdk' } else { env('HOME') + '/Android/Sdk' }))
gradle := 'ANDROID_HOME="' + sdk + '" ./gradlew --max-workers=' + max_workers
adb := sdk + '/platform-tools/adb'
# The phone and wear apps share one applicationId on purpose: the Wearable Data Layer only reaches
# an app with the same package name on the other device. They never share a device, so it never
# collides; the two activities differ.
package := 'fi.nikosavola.ankidroid.wearos'
wear_activity := package + '/' + package + '.wear.MainActivity'
phone_activity := package + '/' + package + '.phone.MainActivity'

# List available recipes
default:
    @just --list

# Run ktfmt, ktlint and cargo fmt over the sources
[group('lint')]
format:
    {{ gradle }} formatAll

# Run ktfmt, ktlint, detekt and cargo clippy, matching the CI "verify" job
[group('lint')]
lint:
    {{ gradle }} lintAll

# Run Android Lint. Needs a resolved SDK, so it is not part of lintAll
[group('lint')]
android-lint:
    {{ gradle }} :phone:lintDebug :wear:lintDebug :ankidroid-provider:lintDebug

# Install prek hooks so format and lint run on every commit
[group('lint')]
install-pre:
    prek install

# Run all pre-commit hooks over every tracked file
[group('lint')]
precommit:
    prek run --all-files

# Cross-compile the Rust core for every ABI and generate the UniFFI Kotlin bindings
[group('build')]
bindings:
    {{ gradle }} generateBindings

# Build both debug APKs. Each one triggers bindings, so this is the whole toolchain in one go
[group('build')]
assemble:
    {{ gradle }} :phone:assembleDebug :wear:assembleDebug

# Build both release APKs and app bundles. Unsigned: there is no signing config in this repo
[group('build')]
assemble-release:
    {{ gradle }} :phone:assembleRelease :phone:bundleRelease :wear:assembleRelease :wear:bundleRelease

# Build the R8, non-debuggable, debug-signed wear APK used for frame timing and baseline profiles
[group('build')]
assemble-benchmark:
    {{ gradle }} :wear:assembleBenchmark

# Check the cross-compiled .so payloads against the 16 KB page-size requirement
[group('build')]
verify-16kb: bindings
    scripts/check_16kb_alignment.sh

# Remove build outputs and the generated bindings
[group('build')]
clean:
    {{ gradle }} clean

# Run the Rust core unit tests
[group('test')]
rust-test:
    cargo test --manifest-path core/Cargo.toml

# Check the vendored rslib sources against their pinned sha256s
[group('test')]
vendor-check:
    scripts/vendor-rslib.sh --check

# Run the host-JVM (Robolectric, MockK) unit tests for both apps
[group('test')]
test:
    {{ gradle }} :phone:testDebugUnitTest :wear:testDebugUnitTest

# Run the instrumented phone tests against a device with AnkiDroid installed. They skip without it
[group('test')]
test-instrumented serial='emulator-5556':
    ANDROID_SERIAL="{{ serial }}" {{ gradle }} :phone:connectedDebugAndroidTest

# Full local gate: lint, build, host tests and the Rust suite, matching CI
[group('test')]
verify:
    {{ gradle }} lintAll :phone:assembleDebug :wear:assembleDebug \
        :phone:testDebugUnitTest :wear:testDebugUnitTest --no-daemon
    cargo test --manifest-path core/Cargo.toml

# Recapture the README screenshots from a Wear OS emulator with demo decks seeded on it
[group('screenshots')]
screenshots serial='emulator-5554':
    scripts/capture-screenshots.sh "{{ serial }}"

# List connected adb devices, including wireless ones
[group('device')]
devices:
    {{ adb }} devices -l

# Connect to a previously paired watch found via mDNS (run `just pair` first if this fails)
[group('device')]
connect:
    #!/usr/bin/env bash
    set -euo pipefail
    ADB="{{ adb }}"
    "$ADB" start-server
    target=$("$ADB" mdns services | grep '_adb-tls-connect._tcp' | awk '{print $3}')
    if [ -z "$target" ]; then
      echo "No paired watch found via mDNS. Enable Wireless debugging on the watch, then run: just pair <ip:port> <code>" >&2
      exit 1
    fi
    "$ADB" connect "$target"

# Pair a watch: get ip:port and code from its Wireless debugging > Pair new device screen
[group('device')]
pair ip_port code:
    {{ adb }} pair {{ ip_port }} {{ code }}

# Rationale for the serials: both apps share one applicationId, so installing on the wrong device
# silently replaces the app that is already there.

# Build and install the debug APK on the watch
[group('device')]
install-wear serial='emulator-5554': assemble
    {{ adb }} -s "{{ serial }}" install --streaming -r wear/build/outputs/apk/debug/wear-debug.apk

# Build and install the debug APK on the phone
[group('device')]
install-phone serial='emulator-5556': assemble
    {{ adb }} -s "{{ serial }}" install --streaming -r phone/build/outputs/apk/debug/phone-debug.apk

# Launch the watch app
[group('device')]
launch-wear serial='emulator-5554':
    {{ adb }} -s "{{ serial }}" shell am start -n {{ wear_activity }}

# Launch the phone app
[group('device')]
launch-phone serial='emulator-5556':
    {{ adb }} -s "{{ serial }}" shell am start -n {{ phone_activity }}

# Stream the watch app's logcat, filtered to crashes and this process
[group('device')]
logcat-wear serial='emulator-5554':
    {{ adb }} -s "{{ serial }}" logcat -s AndroidRuntime System.err {{ package }}

# Stream the phone app's logcat, filtered to crashes and this process
[group('device')]
logcat-phone serial='emulator-5556':
    {{ adb }} -s "{{ serial }}" logcat -s AndroidRuntime System.err {{ package }}.phone

# Remove both apps from a device. One uninstall covers both, they share an applicationId
[group('device')]
uninstall serial='emulator-5554':
    {{ adb }} -s "{{ serial }}" uninstall {{ package }}

# Seed demo decks on an emulator, which cannot pair without the Play Store Wear OS app
[group('device')]
seed-decks serial='emulator-5554': install-wear
    {{ gradle }} :wear:installDebugAndroidTest
    {{ adb }} -s "{{ serial }}" shell am instrument -w -e seed true \
        -e class {{ package }}.wear.DemoDeckSeeder \
        {{ package }}.test/androidx.test.runner.AndroidJUnitRunner

# Boot a Wear OS emulator; plain `-avd` alone segfaults on GPU/display init here, hence the flags
[group('emulator')]
emulator avd='wearos7_large_round':
    {{ sdk }}/emulator/emulator -avd {{ avd }} -no-window -no-audio -no-boot-anim \
        -gpu swiftshader_indirect -no-snapshot

# List available AVDs
[group('emulator')]
avds:
    {{ sdk }}/emulator/emulator -list-avds
