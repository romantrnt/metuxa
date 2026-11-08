#!/usr/bin/env bash
# Read-only offline validation of final artifacts; uses no private signing files.
set -euo pipefail
if [[ -n "${JAVA_HOME:-}" ]]; then export PATH="$JAVA_HOME/bin:$PATH"; fi
root=$(cd "$(dirname "$0")/.." && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
javac -encoding UTF-8 -d "$work" "$root/client/quest/app/src/main/java/com/metashare/client/UpdateManifest.java" "$root/scripts/update/VerifyManifest.java"
java -cp "$work" VerifyManifest "$@"
