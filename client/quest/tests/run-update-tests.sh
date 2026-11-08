#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/app/build/update-tests"
mkdir -p "$OUT"
"${JAVA_HOME:+$JAVA_HOME/bin/}javac" -encoding UTF-8 -Xlint:all -d "$OUT" "$ROOT/app/src/main/java/com/metashare/client/UpdateManifest.java" "$ROOT/app/src/main/java/com/metashare/client/UpdateOperationState.java" "$ROOT/tests/UpdateTests.java"
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -ea -cp "$OUT" com.metashare.client.UpdateTests
