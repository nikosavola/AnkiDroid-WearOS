#!/usr/bin/env bash
# Verify 16 KB page-size compliance (Android 15+ / Wear OS 6): every PT_LOAD segment of the
# 64-bit ABIs must have p_align >= 0x4000. 32-bit ABIs (armeabi-v7a) keep 4 KB pages.
#
# Resolves llvm-readelf from: $LLVM_READELF, PATH (incl. versioned llvm-readelf-N), or the
# NDK's prebuilt toolchain (macOS has no system llvm-readelf; CI runners may only expose
# versioned names).
set -euo pipefail

READELF="${LLVM_READELF:-}"
if [ -z "$READELF" ]; then
  for c in llvm-readelf llvm-readelf-19 llvm-readelf-18 llvm-readelf-17 llvm-readelf-16; do
    if command -v "$c" >/dev/null; then READELF="$c"; break; fi
  done
fi
if [ -z "$READELF" ]; then
  READELF=$(ls -1 \
    "${ANDROID_HOME:-$HOME/Library/Android/sdk}"/ndk/*/toolchains/llvm/prebuilt/*/bin/llvm-readelf \
    2>/dev/null | head -1 || true)
fi
[ -n "$READELF" ] || { echo "error: llvm-readelf not found (set LLVM_READELF or install LLVM/NDK)" >&2; exit 1; }

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
libs=$(find "$ROOT/wear/src/main/jniLibs" "$ROOT/phone/src/main/jniLibs" -name '*.so' 2>/dev/null | sort)
if [ -z "$libs" ]; then
  echo "error: no .so files found - run ./gradlew generateBindings first" >&2
  exit 1
fi

fail=0
checked=0
for so in $libs; do
  case "$(basename "$(dirname "$so")")" in
    arm64-v8a | x86_64) ;;
    *) continue ;;
  esac
  # Min p_align across LOAD segments; llvm-readelf -W prints it as the last column. Bash
  # arithmetic handles the 0x-prefixed hex, so this stays portable to BSD awk.
  min_align=0
  first=1
  while read -r align_hex; do
    align=$((align_hex))
    if [ "$first" -eq 1 ] || [ "$align" -lt "$min_align" ]; then
      min_align=$align
      first=0
    fi
  done < <("$READELF" -lW "$so" | awk '/^  LOAD/ {print $NF}')
  checked=$((checked + 1))
  if [ "$min_align" -lt 16384 ]; then
    echo "FAIL: $so has a LOAD segment with p_align $min_align < 16384" >&2
    fail=1
  else
    echo "ok: $so (min LOAD p_align: $min_align)"
  fi
done

if [ "$checked" -eq 0 ]; then
  echo "error: no 64-bit .so files (arm64-v8a/x86_64) found" >&2
  exit 1
fi
exit $fail