#!/usr/bin/env bash
#
# Runs the instrumented suite on the emulator that the CI job has already started.
#
# The runner occasionally cannot talk to the freshly booted emulator: the device reports API level 1 (so the
# APK cannot be installed), the emulator console never comes up, or the device goes offline. That is
# infrastructure, not a test result. In that case the run is retried once after restarting adb, and the
# retry is announced as a warning so it is visible in the job summary.
#
# A real test failure is not retried: it does not match the patterns below, so it is reported exactly as the
# test runner reported it.
set -u
set -o pipefail

LOG=gradle-output.log

attempt() {
    ./gradlew connectedDebugAndroidTest --console=plain 2>&1 | tee -a "$LOG"
}

# The emulator can be reachable by adb before Android itself has finished booting; without this the first
# attempt can start too early.
wait_for_boot() {
    i=0
    while [ "$i" -lt 60 ]; do
        if [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; then
            return 0
        fi
        i=$((i + 1))
        sleep 5
    done
    return 1
}

wait_for_boot || echo "::warning title=Emulator slow to boot::continuing anyway; the suite will report if the device is unusable"

if attempt; then
    exit 0
fi

if grep -qE "API level=1|Failed to install split APK|Failed to start Emulator console|device offline|error: device" "$LOG"; then
    echo "::warning title=Emulator unreachable::restarting adb and retrying the instrumented suite once"
    adb kill-server || true
    adb start-server || true
    sleep 15
    wait_for_boot || true
    attempt
    exit $?
fi

exit 1
