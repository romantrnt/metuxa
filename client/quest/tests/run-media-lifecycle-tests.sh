#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$(mktemp -d "${TMPDIR:-/tmp}/metuxa-media-tests.XXXXXX")"
trap 'rm -rf "$OUT"' EXIT
JAVAC="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
SRC="$ROOT/app/src/main/java/com/metashare/client"
mapfile -t STUBS < <(find "$ROOT/tests/media" -name '*.java' -print | sort)
"$JAVAC" -encoding UTF-8 -Xlint:all -d "$OUT" "${STUBS[@]}" "$SRC/CodecLifecycle.java" "$SRC/VideoDecoder.java" "$SRC/AudioDecoder.java" "$SRC/MonitorSessionGate.java" "$SRC/SocketShutdown.java"
"$JAVA" -ea -cp "$OUT" com.metashare.client.MediaLifecycleTests
