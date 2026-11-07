#!/bin/sh
set -eu
# Headless private compositor, private D-Bus, temporary preferences. No access to
# the real desktop and no actual metuxa server/capture process is ever started.
# Usage: run-isolated-ui.sh /absolute/path/metuxa-ui-tests /optional/screenshots
binary=$(realpath "$1")
shots=${2:-}
work=$(mktemp -d /tmp/metuxa-ui-XXXXXX)
chmod 700 "$work"
cleanup() {
    if [ -n "${weston_pid:-}" ]; then kill "$weston_pid" 2>/dev/null || true; wait "$weston_pid" 2>/dev/null || true; fi
    rm -rf "$work"
}
trap cleanup EXIT INT TERM
export XDG_RUNTIME_DIR="$work/runtime" XDG_CONFIG_HOME="$work/config" XDG_CACHE_HOME="$work/cache" XDG_DATA_HOME="$work/data"
mkdir -m 700 "$XDG_RUNTIME_DIR" "$XDG_CONFIG_HOME" "$XDG_CACHE_HOME" "$XDG_DATA_HOME"
unset DISPLAY DBUS_SESSION_BUS_ADDRESS WAYLAND_SOCKET
# Deliberately no service directories: tests cannot activate desktop portals,
# a keyring, or any other real desktop service through the private bus.
cat > "$work/dbus.conf" <<'DBUS'
<busconfig>
  <type>session</type>
  <listen>unix:tmpdir=/tmp</listen>
  <auth>EXTERNAL</auth>
  <policy context="default">
    <allow own="*"/>
    <allow send_destination="*"/>
    <allow receive_sender="*"/>
  </policy>
</busconfig>
DBUS
export WAYLAND_DISPLAY="metuxa-test-$$" GDK_BACKEND=wayland GSK_RENDERER=cairo GTK_A11Y=none GSETTINGS_BACKEND=memory GIO_USE_VFS=local GTK_USE_PORTAL=0 METUXA_TEST_ISOLATED=1
if [ -n "$shots" ]; then mkdir -p "$shots"; export METUXA_TEST_SCREENSHOTS="$(realpath "$shots")"; fi
weston --backend=headless --renderer=pixman --shell=desktop --socket="$WAYLAND_DISPLAY" --width=1100 --height=1050 --idle-time=0 --no-config --log="$work/weston.log" &
weston_pid=$!
count=0
while [ ! -S "$XDG_RUNTIME_DIR/$WAYLAND_DISPLAY" ]; do
    count=$((count+1)); if [ "$count" -gt 100 ]; then cat "$work/weston.log"; exit 1; fi
    sleep 0.05
done
timeout 25 dbus-run-session --config-file="$work/dbus.conf" -- "$binary"
