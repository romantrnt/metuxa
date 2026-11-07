#!/usr/bin/env bash
# Package already-built local test artifacts. Never installs, starts or publishes.
set -euo pipefail
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="${1:?Usage: package-native-test.sh /new/output/directory}"
[[ ! -e "$OUT" ]] || { echo 'Refusing to overwrite an existing delivery directory.' >&2; exit 1; }
BUILD="${BUILD_DIR:-$ROOT/build-native}"
APK="$ROOT/client/quest/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk"
for file in "$BUILD/src/streamer/metuxa-server" "$BUILD/client/gtk/metuxa-control" "$APK"; do
  [[ -s "$file" ]] || { echo "Missing built artifact: $file" >&2; exit 1; }
done
mkdir -p "$OUT/bin" "$OUT/lib" "$OUT/licenses/sdbus-c++" "$OUT/verification" "$OUT/share/icons/hicolor/scalable/apps" "$OUT/docs"
cp "$BUILD/src/streamer/metuxa-server" "$BUILD/client/gtk/metuxa-control" "$OUT/bin/"
cp "$APK" "$OUT/metuxa-quest-0.2.0-debug.apk"
cp -a "$ROOT/.deps/sdbus/lib/"libsdbus-c++.so* "$OUT/lib/"
cp "$ROOT/LICENSE" "$ROOT/THIRD-PARTY-NOTICES.md" "$OUT/"
cp "$ROOT/docs/"*.md "$OUT/docs/"
cp "$ROOT/data/brand/metuxa-icon.svg" "$OUT/share/icons/hicolor/scalable/apps/dev.metuxa.Control.svg"
cat > "$OUT/start-metuxa.sh" <<'START'
#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
export LD_LIBRARY_PATH="$ROOT/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
export PATH="$ROOT/bin:$PATH"
export XDG_DATA_DIRS="$ROOT/share:${XDG_DATA_DIRS:-/usr/local/share:/usr/share}"
exec "$ROOT/bin/metuxa-control"
START
chmod +x "$OUT/start-metuxa.sh"
printf 'Test folder prepared: %s\n' "$OUT"
