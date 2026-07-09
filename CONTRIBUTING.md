# Contributing

Build, format, and test instructions are summarized here; see `README.md` for the architecture
overview and `CLAUDE.md` for the deeper conventions.

## Build & test

```bash
# Cross-compile the Rust core for all ABIs + generate the UniFFI Kotlin bindings
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew generateBindings
# Compile both apps
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew compileDebugKotlin
# Rust core unit tests
cargo test --manifest-path core/Cargo.toml
# JVM (Robolectric/MockK) unit tests
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew testDebugUnitTest
# Format + lint everything (ktfmt, ktlint, detekt, cargo fmt/clippy, yaml, actions)
uv run prek run --all-files
```

## Local Android emulator (redroid, via Docker)

For device verification without a physical phone, run Android in a container with
[redroid](https://github.com/remote-android/redroid-doc). redroid shares the host kernel (no
KVM/AVD, lightweight and headless), which makes it well suited to CI boxes and remote dev VMs. This
covers the **phone** side: the AnkiDroid `ContentProvider` write-back and phone-UI rendering.

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
