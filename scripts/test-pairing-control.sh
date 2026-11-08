#!/usr/bin/env bash
# Run real main.cpp pairing IPC tests without desktop, input, audio or LAN access.
# Native: bash scripts/test-pairing-control.sh /path/to/metuxa-server
# Package: bash scripts/test-pairing-control.sh /path/to/AppRun --server
set -euo pipefail
[[ $# -ge 1 ]] || { echo "Usage: $0 SERVER [FIXED_SERVER_ARGUMENTS...]" >&2; exit 2; }
ROOT=$(cd -- "$(dirname -- "$0")/.." && pwd)
for tool in unshare ip python3; do command -v "$tool" >/dev/null; done
work=$(mktemp -d /tmp/metuxa-pairing-control.XXXXXXXX)
chmod 700 "$work"
trap 'rm -rf "$work"' EXIT
mkdir -m 700 "$work/runtime" "$work/home" "$work/config" "$work/cache"
unset DISPLAY WAYLAND_DISPLAY WAYLAND_SOCKET DBUS_SESSION_BUS_ADDRESS
export XDG_RUNTIME_DIR="$work/runtime" XDG_CONFIG_HOME="$work/config" XDG_CACHE_HOME="$work/cache" HOME="$work/home"
export PIPEWIRE_REMOTE=metuxa-test-no-host-connection PULSE_SERVER=unix:/nonexistent
export METUXA_TEST_PARENT_NETNS
METUXA_TEST_PARENT_NETNS=$(readlink /proc/self/ns/net)
unshare --user --map-root-user --net bash -c 'ip link set lo up; exec python3 "$@"' _ \
    "$ROOT/tests/pairing_control_integration.py" "$@"
