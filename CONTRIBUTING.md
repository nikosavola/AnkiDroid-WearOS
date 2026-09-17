# Contributing

Build, format, and test instructions are summarized here; see `README.md` for the architecture
overview and `AGENTS.md` for the deeper conventions.

## Build & test

`JAVA_HOME` must point at a JDK below. JDK 21 is the default (what CI uses, and the apps'
bytecode target). Linux `/usr/lib/jvm/java-21-openjdk-amd64`, macOS
`/opt/homebrew/opt/openjdk@21` (`brew install openjdk@21`). Any JDK 17-26 also works as
the Gradle launcher JVM (Gradle 9.4 supports up to Java 26); the foojay toolchain resolver
auto-provisions what the build itself needs.

```bash
# Cross-compile the Rust core for all ABIs + generate the UniFFI Kotlin bindings
JAVA_HOME=<jdk21> ./gradlew generateBindings
# Compile both apps
JAVA_HOME=<jdk21> ./gradlew compileDebugKotlin
# Rust core unit tests
cargo test --manifest-path core/Cargo.toml
# JVM (Robolectric/MockK) unit tests
JAVA_HOME=<jdk21> ./gradlew testDebugUnitTest
# Format + lint everything (ktfmt, ktlint, detekt, cargo fmt/clippy, yaml, actions)
uv run prek run --all-files
```

CI runs the Rust tests, the JVM unit tests, Android lint, an unsigned release build, a 16 KB
page-size check, and the workflow/config security hooks on every push. The **instrumented** tests
(`./gradlew :phone:connectedDebugAndroidTest`) are not in CI — they need a real AnkiDroid install —
and every test in them skips unless one is present, so they are safe to run anywhere.
`AnkiDroidProviderLiveTest` documents the two emulator setup traps (the runtime permission is
dropped by each reinstall; AnkiDroid answers every provider query with a null cursor until it can
create its collection directory). What CI cannot cover —
the GMS Data Layer hop, rotary input, tiles, TTS, battery behavior — is listed as a manual
pre-release checklist in `MANUAL_TESTING.md`.

## Local Android emulator (native AVDs, macOS)

**redroid does not work on macOS**: Docker Desktop's LinuxKit kernel ships with neither
the `binder_linux` module nor built-in binder support (`CONFIG_ANDROID_BINDER_IPC is not
set`), and there is no root access to that VM's kernel to load one. Use the native
emulator instead — on Apple Silicon it runs at near-native speed via Hypervisor.framework
(no KVM/AVD-in-VM needed), and it is also the only option that can run the Wear OS side
(redroid is phone AOSP without GMS, so the Data Layer never works there anyway).

### One-time setup

```bash
brew install openjdk@21
# SDK command line tools: unzip https://dl.google.com/android/repository/commandlinetools-mac-11076708_latest.zip
# into ~/Library/Android/sdk/cmdline-tools/latest, then:
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
~/Library/Android/sdk/cmdline-tools/latest/bin/sdkmanager --install \
  "platform-tools" "platforms;android-36" "build-tools;36.0.0" \
  "ndk;27.0.12077973" "emulator" \
  "system-images;android-36-ext19;google_apis;arm64-v8a" \
  "system-images;android-36;android-wear-signed;arm64-v8a"

~/Library/Android/sdk/cmdline-tools/latest/bin/avdmanager create avd \
  -n wear_api36 -k "system-images;android-36;android-wear-signed;arm64-v8a" -d wearos_large_round
~/Library/Android/sdk/cmdline-tools/latest/bin/avdmanager create avd \
  -n phone_api36 -k "system-images;android-36-ext19;google_apis;arm64-v8a" -d pixel_7

rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
cargo install cargo-ndk
```

### Run & connect

```bash
~/Library/Android/sdk/emulator/emulator -avd wear_api36 -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect &
~/Library/Android/sdk/emulator/emulator -avd phone_api36 -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect &
# adb ports are assigned in launch order (5554, 5556, ...); map them with:
adb -s emulator-5554 emu avd name
# Boot is done when this prints 1:
adb -s emulator-5554 shell getprop sys.boot_completed
```

Drop `-no-window` to get the normal emulator windows — recommended for watch-UI work.

### Install & inspect the app

```bash
./gradlew :phone:assembleDebug :wear:assembleDebug
adb -s emulator-5554 install -r wear/build/outputs/apk/debug/wear-debug.apk   # watch
adb -s emulator-5556 install -r phone/build/outputs/apk/debug/phone-debug.apk # phone
adb -s emulator-5554 shell am start -n fi.nikosavola.ankidroid.wearos/.wear.MainActivity
adb -s emulator-5556 shell am start -n fi.nikosavola.ankidroid.wearos/.phone.MainActivity
adb -s emulator-5554 exec-out screencap -p > watch.png  # visual check
```

Both apps share the `fi.nikosavola.ankidroid.wearos` application id; the launcher activities are
`.wear.MainActivity` and `.phone.MainActivity` respectively. Without AnkiDroid installed on
the phone emulator, debug builds fall back to mock cards (see README §2), so the full
review flow is testable with just the two AVDs; release builds need the Force Mock Mode
settings toggle enabled explicitly. The phone<->watch GMS Data Layer hop needs
both AVDs' Play Services to settle after first boot; if syncing does not start, let the
emulators sit for a few minutes after a fresh cold boot.

## Local Android emulator (redroid, via Docker)

For device verification without a physical phone, run Android in a container with
[redroid](https://github.com/remote-android/redroid-doc). redroid shares the host kernel (no
KVM/AVD, lightweight and headless), which makes it well suited to CI boxes and remote dev VMs —
**Linux hosts only** (see the native-AVD section above for macOS). This covers the **phone** side:
the AnkiDroid `ContentProvider` write-back and phone-UI rendering.

### Prerequisites

- Docker (the commands below use a privileged container).
- The host kernel's `binder` module. It ships with most Ubuntu kernels as a module; load it once
  (survives until reboot). If you have root:

  ```bash
  sudo modprobe binder_linux devices="binder,hwbinder,vndbinder"
  ```

  If you only have Docker access (no root login), a privileged container can load it into the shared
  host kernel:

  ```bash
  docker run --rm --privileged -v /lib/modules:/lib/modules:ro alpine \
    sh -c 'apk add -q kmod && modprobe binder_linux devices="binder,hwbinder,vndbinder"'
  ```

  Verify: `lsmod | grep binder` shows `binder_linux`, and `grep binder /proc/filesystems` lists it.

### Run & connect

```bash
docker run -itd --privileged --name redroid \
  -v "$HOME/redroid-data:/data" -p 5555:5555 \
  redroid/redroid:13.0.0-latest \
  androidboot.redroid_gpu_mode=guest    # software GPU; headless-friendly

adb connect localhost:5555
adb -s localhost:5555 wait-for-device
# Boot is done when this prints 1:
adb -s localhost:5555 shell getprop sys.boot_completed
```

`docker start redroid` resumes it later (Android state persists in `~/redroid-data`).

### Install & inspect the app

```bash
./gradlew :phone:assembleDebug
adb -s localhost:5555 install -r phone/build/outputs/apk/debug/phone-debug.apk
adb -s localhost:5555 shell am start -n fi.nikosavola.ankidroid.wearos/.phone.MainActivity
adb -s localhost:5555 exec-out screencap -p > shot.png   # visual check
```

### Caveats

- The `redroid/redroid` image is AOSP with **no Google Play Services**, so the phone↔watch
  Wearable Data Layer does not work here. Use it for the AnkiDroid write-back path and phone-UI
  work; use a paired phone + Wear device (or a Wear AVD) for the sync transfer and the watch UI.
- redroid is **phone Android, not Wear OS** — the watch app installs but its round-display Compose
  is meant for a Wear device.
- Benign noise: the container's Bluetooth HAL logs `SIGABRT` in `logcat` (no BT hardware); ignore it.
- To exercise write-back, also install an **x86_64** AnkiDroid build, open it once to create a
  collection, then grant the companion the `com.ichi2.anki.permission.READ_WRITE_DATABASE`
  permission.
