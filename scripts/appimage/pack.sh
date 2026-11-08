#!/usr/bin/env bash
# Run with mksquashfs from the pinned Ubuntu builder for repeatable packaging.
set -euo pipefail
WORK=${WORK:-/work}
OUTPUT=${1:-$WORK/metuxa-0.4.2-x86_64.AppImage}
EPOCH=${SOURCE_DATE_EPOCH:-1794096000}
RUNTIME="$WORK/runtime-build/out/runtime-x86_64"
[[ -f "$WORK/AppDir/usr/share/metuxa/SOURCE-INDEX.json" && -x "$RUNTIME" ]]
[[ ! -e "$OUTPUT" ]] || { echo 'Refusing to overwrite existing release artifact' >&2; exit 1; }
python3 - "$RUNTIME" /source/scripts/appimage/sources.lock.json <<'PY'
import hashlib,json,sys
expected=json.load(open(sys.argv[2]))['appimage_runtime']['rebuilt_binary_sha256']
assert hashlib.sha256(open(sys.argv[1],'rb').read()).hexdigest()==expected,'Runtime hash mismatch; explicitly audit and record a changed build before release'
PY
PAYLOAD=$(mktemp "$WORK/payload.XXXXXXXX")
trap 'rm -f "$PAYLOAD"' EXIT
mksquashfs "$WORK/AppDir" "$PAYLOAD" -noappend -all-root -no-xattrs \
  -all-time "$EPOCH" -mkfs-time "$EPOCH" -b 1048576 -comp zstd -Xcompression-level 15 -processors 2
cat "$RUNTIME" "$PAYLOAD" > "$OUTPUT"
chmod 755 "$OUTPUT"
"$OUTPUT" --appimage-extract-and-run --validate
sha256sum "$OUTPUT"
