#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
: "${JAVA_HOME:?Set JAVA_HOME to the existing JDK 17 installation}"
: "${ANDROID_HOME:?Set ANDROID_HOME to the existing Android SDK with platform 34}"
GRADLE="${GRADLE:-gradle}"
MODE=(--offline)
if [[ "${1:-}" == --online ]]; then MODE=(); shift; fi
if [[ $# -ne 0 ]]; then echo "Usage: $0 [--online]" >&2; exit 2; fi
mkdir -p "$ROOT/app/build/ui-qa/screenshots"
"$GRADLE" -p "$ROOT" -I "$ROOT/tests/ui/init.gradle" "${MODE[@]}" --no-daemon \
  :app:testDebugUnitTest --tests com.metashare.client.ThemeRenderTest --tests com.metashare.client.StreamShutdownTest
