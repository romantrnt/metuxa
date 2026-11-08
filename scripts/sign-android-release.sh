#!/usr/bin/env bash
# Sign an already built release APK with an existing private release identity.
# Never generates keys, prints passwords, installs an APK, or uploads anything.
set -euo pipefail
if [[ -n "${JAVA_HOME:-}" ]]; then export PATH="$JAVA_HOME/bin:$PATH"; fi
umask 077
if [[ $# != 2 ]]; then echo "Usage: $0 unsigned-release.apk output.apk" >&2; exit 2; fi
: "${ANDROID_HOME:?Set ANDROID_HOME to an installed Android SDK}"
SIGNING_DIR="${METUXA_SIGNING_DIR:-$HOME/.local/share/metuxa/signing}"
BUILD_TOOLS="${METUXA_ANDROID_BUILD_TOOLS:-$ANDROID_HOME/build-tools/34.0.0}"
INPUT=$(realpath "$1")
OUTPUT=$(realpath -m "$2")
[[ "$INPUT" != "$OUTPUT" ]] || { echo 'Input and output must differ' >&2; exit 2; }
[[ -f "$SIGNING_DIR/metuxa-release.p12" && -f "$SIGNING_DIR/keystore.password" ]] || { echo 'Existing private release identity required' >&2; exit 2; }
[[ ! -e "$OUTPUT" ]] || { echo 'Output exists; choose a new path rather than overwriting a published artifact' >&2; exit 2; }
badging=$("$BUILD_TOOLS/aapt" dump badging "$INPUT")
[[ "$badging" == *"package: name='io.github.romantrnt.metuxa'"* ]] || { echo 'Unexpected application ID' >&2; exit 2; }
[[ "$badging" != *"application-debuggable"* ]] || { echo 'Refusing a debuggable APK' >&2; exit 2; }
mkdir -p "$(dirname "$OUTPUT")"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
"$BUILD_TOOLS/zipalign" -f -p 4 "$INPUT" "$work/aligned.apk"
"$BUILD_TOOLS/apksigner" sign --ks "$SIGNING_DIR/metuxa-release.p12" \
  --ks-key-alias metuxa-release --ks-pass "file:$SIGNING_DIR/keystore.password" \
  --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled true \
  --v4-signing-enabled false --out "$work/signed.apk" "$work/aligned.apk"
"$BUILD_TOOLS/apksigner" verify --verbose --print-certs "$work/signed.apk"
"$BUILD_TOOLS/zipalign" -c -p 4 "$work/signed.apk"
cp "$work/signed.apk" "$OUTPUT"
chmod 644 "$OUTPUT"
sha256sum "$OUTPUT"
