#!/usr/bin/env bash
# emulator-test.sh — provision a headless Android emulator and run the
# fp-backends-mobile conformance / engine-smoke suite on-device, end to end:
# install the system image + emulator package if absent, create AVD
# "fauste-test" if absent, boot headless (audio kept ON — AAudio needs the
# virtual sound device; host audio is not required), wait for boot completion,
# build the test harness, adb push + adb shell the tests, and shut the emulator
# down (via trap, also on failure). Exits non-zero on any failure.
#
# KVM is required: -no-accel is not acceptable for these timing-sensitive
# tests. cargo-ndk 4.1.2 passes `cargo ndk test` through to `cargo` and does
# NOT deploy/run test binaries on a device, so this script uses the documented
# fallback: `cargo ndk -t x86_64 -P 26 test --no-run` (build) + adb push +
# `adb shell LD_LIBRARY_PATH=...` (run). That path is what applies here.
set -u
set -o pipefail
die() { echo "emulator-test: FAIL: $*" >&2; exit 1; }
say() { echo "emulator-test: $*"; }
export PATH="$HOME/.cargo/bin:$PATH"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
AVDMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager"
ADB="$ANDROID_HOME/platform-tools/adb"
EMULATOR="$ANDROID_HOME/emulator/emulator"
AVD_NAME="fauste-test"
AVD_HOME="${ANDROID_AVD_HOME:-$HOME/.android}"
IMAGE="system-images;android-34;google_apis;x86_64"
SERIAL=""
EMULATOR_PID=""

cleanup() {
    rc=$?
    if [ -n "$EMULATOR_PID" ]; then
        say "shutting down emulator ($SERIAL)"
        [ -n "$SERIAL" ] && "$ADB" -s "$SERIAL" emu kill >/dev/null 2>&1 || true
        kill "$EMULATOR_PID" 2>/dev/null || true
    fi
    exit "$rc"
}
trap cleanup EXIT

[ -x "$ADB" ] || die "adb missing ($ADB)"
[ -x "$SDKMANAGER" ] || die "sdkmanager missing"
command -v cargo >/dev/null || die "cargo missing"
[ -r /dev/kvm ] && [ -w /dev/kvm ] || die "KVM not usable"

install_pkg() {
    # No 'grep -q': with pipefail, grep quitting early kills sdkmanager with
    # SIGPIPE and the pipeline reports 141 even on success.
    if "$SDKMANAGER" --list_installed 2>/dev/null | grep -- "$1" >/dev/null; then
        say "package present: $1"
    else
        say "installing $1"
        # 'yes' answers license prompts; it dies with SIGPIPE when sdkmanager
        # stops reading, so do not trust the pipeline status — verify instead.
        yes | "$SDKMANAGER" --install "$1" || true
        "$SDKMANAGER" --list_installed 2>/dev/null | grep -- "$1" >/dev/null \
            || die "sdkmanager install $1 failed"
    fi
}

install_pkg "$IMAGE"
install_pkg emulator

if [ -d "$AVD_HOME/avd/$AVD_NAME.avd" ]; then
    say "AVD present: $AVD_NAME"
else
    say "creating AVD: $AVD_NAME"
    echo no | "$AVDMANAGER" create avd -n "$AVD_NAME" -k "$IMAGE" -d pixel_6 --force \
        || die "avdmanager create avd failed"
fi

SERIAL=$("$ADB" devices | awk '$1 ~ /^emulator-/ && $2 == "device" { print $1; exit }')
if [ -z "$SERIAL" ]; then
    SERIAL="emulator-5554"
    say "booting $AVD_NAME (this takes 1-3 min)"
    "$EMULATOR" -avd "$AVD_NAME" -no-window -gpu swiftshader_indirect \
        -no-boot-anim -no-metrics -no-snapshot-save \
        >/tmp/emulator-test-boot.log 2>&1 &
    EMULATOR_PID=$!
fi

say "waiting for boot on $SERIAL"
deadline=$(( $(date +%s) + 600 ))
while true; do
    if [ -n "$EMULATOR_PID" ] && ! kill -0 "$EMULATOR_PID" 2>/dev/null; then
        tail -20 /tmp/emulator-test-boot.log >&2 || true
        die "emulator exited during boot"
    fi
    done_flag=$("$ADB" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)
    [ "$done_flag" = "1" ] && break
    [ "$(date +%s)" -gt "$deadline" ] && die "boot timeout on $SERIAL"
    sleep 3
done
say "booted: $SERIAL"

say "building the test harness (cargo ndk -t x86_64 -P 26 test --no-run)"
cargo ndk -t x86_64 -P 26 test --no-run -p fp-backends-mobile || die "ndk test build failed"

TARGET_DIR="target/x86_64-linux-android/debug/deps"
[ -d "$TARGET_DIR" ] || die "no android build directory ($TARGET_DIR)"
found=0
overall=0
# The decode_smoke glob has no cfg gate in the test: `-` is the hash suffix
# convention of the other suites, `_*` needed because the crate name uses it.
for t in "$TARGET_DIR"/conformance-* "$TARGET_DIR"/engine_smoke-* "$TARGET_DIR"/decode_smoke-*; do
    [ -x "$t" ] || continue
    found=1
    name=$(basename "$t")
    "$ADB" -s "$SERIAL" push "$t" "/data/local/tmp/$name" || die "adb push $name failed"
    say "running $name"
    if "$ADB" -s "$SERIAL" shell "cd /data/local/tmp && LD_LIBRARY_PATH=/data/local/tmp ./$name"; then
        say "PASS: $name"
    else
        say "FAIL: $name"
        overall=1
    fi
done
[ "$found" = 1 ] || die "no test binaries found"
exit "$overall"
# Tests done above; trap cleanup handles the emulator shutdown.
