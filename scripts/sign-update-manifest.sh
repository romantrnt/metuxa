#!/usr/bin/env bash
# Sign only with the existing release identity; creates no credentials.
set -euo pipefail
umask 077
if [[ -n "${JAVA_HOME:-}" ]]; then export PATH="$JAVA_HOME/bin:$PATH"; fi
if [[ $# != 7 ]]; then echo 'Usage: sign-update-manifest.sh VERSION CODE PROTOCOL_MIN PROTOCOL_MAX APK APPIMAGE OUTPUT' >&2; exit 2; fi
: "${ANDROID_HOME:?Set ANDROID_HOME to the existing Android SDK}"
tools="${METUXA_ANDROID_BUILD_TOOLS:-$ANDROID_HOME/build-tools/34.0.0}"
cert=$({ "$tools/apksigner" verify --print-certs "$5"; } | sed -n 's/^Signer #1 certificate SHA-256 digest: //p')
[[ "$cert" == '5f5504991278b256e2f890c9b81f6fe5979c978225f24827000bd79c4e6cfdc7' ]] || { echo 'APK release signer mismatch' >&2; exit 2; }
badging=$("$tools/aapt" dump badging "$5")
[[ "$badging" == *"package: name='dev.metuxa.quest' versionCode='$2' versionName='$1'"* && "$badging" != *"application-debuggable"* ]] || { echo 'APK identity/version/architecture mismatch or debuggable build' >&2; exit 2; }
exec java "$(dirname "$0")/update/SignManifest.java" "$@" "${METUXA_SIGNING_DIR:-$HOME/.local/share/metuxa/signing}"
