#!/usr/bin/env bash
# Private real compositor/PipeWire integration. No host bus, display, devices,
# home configuration or network enters this namespace. Never invoke the probe
# against a user's desktop. Existing system tools only; no downloads.
set -euo pipefail
ROOT=$(cd -- "$(dirname -- "$0")/.." && pwd)
BINARY=$(realpath "${1:?Usage: run-isolated-mutter.sh /path/to/mutter-orientation-test [output-dir]}")
OUTPUT=${2:-$ROOT/test-output/mutter-orientation}
mkdir -p "$OUTPUT"
OUTPUT=$(realpath "$OUTPUT")
for tool in bwrap dbus-run-session mutter pipewire wireplumber; do command -v "$tool" >/dev/null; done
LIB_MOUNT=()
LIB_PATH=
if [[ -d "$ROOT/.deps/sdbus/lib" ]]; then
    LIB_MOUNT=(--ro-bind "$ROOT/.deps/sdbus/lib" /tmp/sdbus)
    LIB_PATH=/tmp/sdbus
fi
bwrap --unshare-all --die-with-parent --ro-bind / / --tmpfs /run --tmpfs /home --tmpfs /tmp \
    --tmpfs /usr/share/dbus-1/services --dev /dev --proc /proc \
    "${LIB_MOUNT[@]}" --ro-bind "$BINARY" /tmp/probe \
    --bind "$OUTPUT" /tmp/results \
    env -i PATH=/usr/bin:/bin HOME=/tmp/home XDG_RUNTIME_DIR=/tmp/runtime \
    XDG_CONFIG_HOME=/tmp/config XDG_CACHE_HOME=/tmp/cache XDG_DATA_HOME=/tmp/data \
    XDG_SESSION_TYPE=wayland XDG_CURRENT_DESKTOP=GNOME WAYLAND_DISPLAY=metuxa-isolated \
    LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe GSETTINGS_BACKEND=memory \
    GTK_A11Y=none LD_LIBRARY_PATH="$LIB_PATH" METUXA_ISOLATED_MUTTER=1 \
    bash -eu -c '
      mkdir -m 700 -p "$HOME" "$XDG_RUNTIME_DIR" "$XDG_CONFIG_HOME" "$XDG_CACHE_HOME" "$XDG_DATA_HOME"
      exec dbus-run-session -- timeout 35 bash -eu -c '\''
        pipewire > /tmp/results/pipewire.log 2>&1 & pw=$!
        wireplumber > /tmp/results/wireplumber.log 2>&1 & wp=$!
        mutter --headless --wayland --no-x11 --wayland-display metuxa-isolated --virtual-monitor 1280x720 > /tmp/results/mutter.log 2>&1 & wm=$!
        trap "kill $wm $wp $pw 2>/dev/null || true; wait 2>/dev/null || true" EXIT
        for i in {1..100}; do
          if gdbus introspect --session --dest org.gnome.Mutter.ScreenCast --object-path /org/gnome/Mutter/ScreenCast >/dev/null 2>&1; then break; fi
          kill -0 "$wm" 2>/dev/null || { cat /tmp/results/mutter.log; exit 1; }
          sleep .1
        done
        /tmp/probe > /tmp/results/probe.log 2>&1 || { cat /tmp/results/probe.log; exit 1; }
        cat /tmp/results/probe.log
      '\''
    '
