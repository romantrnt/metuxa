#!/usr/bin/env bash
# Verify the extracted release's own server/libraries with synthetic media only.
# Requires unprivileged user/network namespaces and an existing ffmpeg + Python
# cryptography installation. No real capture, host input, GPU or desktop bus.
set -euo pipefail
[[ $# == 1 ]] || { echo "Usage: $0 /path/to/metuxa.AppImage" >&2; exit 2; }
ROOT=$(cd -- "$(dirname -- "$0")/.." && pwd)
APPIMAGE=$(realpath "$1")
for tool in unshare ip python3 ffmpeg; do command -v "$tool" >/dev/null; done
python3 -c 'from cryptography.hazmat.primitives.ciphers.aead import AESGCM'
work=$(mktemp -d /tmp/metuxa-packaged-stream.XXXXXXXX)
chmod 700 "$work"
trap 'rm -rf "$work"' EXIT
(cd "$work"; "$APPIMAGE" --appimage-extract >/dev/null)
[[ -x "$work/squashfs-root/AppRun" ]]
mkdir -m 700 "$work/runtime" "$work/home" "$work/config" "$work/cache"
cat > "$work/server" <<'WRAPPER'
#!/bin/sh
exec "$METUXA_TEST_APPDIR/AppRun" --server "$@"
WRAPPER
chmod 700 "$work/server"
unset DISPLAY WAYLAND_DISPLAY WAYLAND_SOCKET DBUS_SESSION_BUS_ADDRESS
export XDG_RUNTIME_DIR="$work/runtime" XDG_CONFIG_HOME="$work/config" XDG_CACHE_HOME="$work/cache" HOME="$work/home"
export METUXA_TEST_APPDIR="$work/squashfs-root"
export PIPEWIRE_REMOTE=metuxa-test-no-host-connection PULSE_SERVER=unix:/nonexistent
unshare --user --map-root-user --net bash -c 'ip link set lo up; exec python3 "$1" "$2"' _ "$ROOT/tests/server_integration.py" "$work/server"
