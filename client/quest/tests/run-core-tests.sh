#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/app/build/core-tests"
mkdir -p "$OUT"
JAVAC="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
SRC="$ROOT/app/src/main/java/com/metashare/client"
"$JAVAC" -encoding UTF-8 -Xlint:all -d "$OUT" "$SRC/DiscoveryBudget.java" "$SRC/HostEndpoint.java" "$SRC/InputState.java" "$SRC/PointerPolicy.java" "$SRC/InputDiagnostics.java" "$SRC/KeyboardQueue.java" "$SRC/InputLifecycle.java" "$SRC/InputOptions.java" "$SRC/PointerOwnership.java" "$SRC/HardwareKeyLedger.java" "$ROOT/tests/CoreTests.java"
"$JAVA" -ea -cp "$OUT" com.metashare.client.CoreTests
python3 "$ROOT/tests/static-checks.py"
