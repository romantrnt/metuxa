#!/usr/bin/env bash
# Needs bubblewrap, Weston and dbus-run-session already installed on the tester.
# No host display/bus, network, GPU, input or PipeWire sockets enter the test.
set -euo pipefail
ROOT=$(cd -- "$(dirname -- "$0")/../.." && pwd)
WORK=${WORK:-$ROOT/.release-work}
mkdir -p "$WORK/isolated-ui"
APPDIR="$WORK/AppDir" TEST_BINARY="$WORK/build-app/client/gtk/metuxa-ui-tests" \
bwrap --unshare-all --die-with-parent --ro-bind / / --tmpfs /tmp --tmpfs /usr/share/dbus-1/services --dev /dev --proc /proc \
  --bind "$WORK/isolated-ui" /tmp/results \
  env -i PATH=/usr/bin:/bin APPDIR="$WORK/AppDir" \
  TEST_BINARY="$WORK/build-app/client/gtk/metuxa-ui-tests" \
  HOME=/tmp/metuxa-ui-home XDG_RUNTIME_DIR=/tmp/metuxa-ui-runtime \
  XDG_CONFIG_HOME=/tmp/metuxa-ui-config XDG_CACHE_HOME=/tmp/metuxa-ui-cache \
  XDG_DATA_HOME=/tmp/metuxa-ui-data GDK_BACKEND=wayland \
  WAYLAND_DISPLAY=metuxa-test-appimage GSK_RENDERER=cairo GTK_A11Y=none \
  METUXA_TEST_ISOLATED=1 METUXA_TEST_SCREENSHOTS=/tmp/results \
  dbus-run-session -- bash -eu -c '
    mkdir -p "$HOME" "$XDG_RUNTIME_DIR" "$XDG_CONFIG_HOME" "$XDG_CACHE_HOME"
    chmod 700 "$XDG_RUNTIME_DIR"
    weston --backend=headless --renderer=pixman --socket="$WAYLAND_DISPLAY" --idle-time=0 --log=/tmp/results/weston.log &
    compositor=$!
    trap "kill $compositor 2>/dev/null || true" EXIT
    for i in {1..100}; do [[ ! -S "$XDG_RUNTIME_DIR/$WAYLAND_DISPLAY" ]] || break; sleep .05; done
    [[ -S "$XDG_RUNTIME_DIR/$WAYLAND_DISPLAY" ]]
    export LD_LIBRARY_PATH="$APPDIR/usr/lib"
    export XDG_DATA_DIRS="$APPDIR/usr/share:/usr/local/share:/usr/share"
    export GSETTINGS_SCHEMA_DIR="$APPDIR/usr/share/glib-2.0/schemas"
    export FONTCONFIG_PATH="$APPDIR/usr/etc/fonts"
    export FONTCONFIG_FILE=fonts.conf
    export FONTCONFIG_TEMPLATE_DIR="$APPDIR/usr/etc/fonts/conf.avail"
    export GIO_MODULE_DIR="$APPDIR/usr/lib/gio/modules"
    export GDK_PIXBUF_MODULE_FILE=/tmp/metuxa-ui-loaders.cache
    sed "s|@APPDIR@|$APPDIR|g" "$APPDIR/usr/lib/gdk-pixbuf-loaders.cache.in" > "$GDK_PIXBUF_MODULE_FILE"
    timeout 25 "$TEST_BINARY" > /tmp/results/ui-tests.log 2>&1
    # Launch the actual packaged control binary, without pressing Start.
    set +e
    timeout --signal=TERM 3 "$APPDIR/AppRun" > /tmp/results/control-startup.log 2>&1
    status=$?
    set -e
    [[ $status == 124 ]]
    ! grep -Ei "symbol lookup error|error while loading shared libraries|Failed to open display|cannot open display|Gtk-ERROR" /tmp/results/control-startup.log
    echo "PASS: packaged control startup in private Weston session" >> /tmp/results/control-startup.log
  '
cat "$WORK/isolated-ui/ui-tests.log" "$WORK/isolated-ui/control-startup.log"
