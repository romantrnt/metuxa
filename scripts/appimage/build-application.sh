#!/usr/bin/env bash
set -euo pipefail
WORK=${WORK:-/work}
SOURCE=${SOURCE:-/source}
export SOURCE_DATE_EPOCH=${SOURCE_DATE_EPOCH:-1794096000}
export PKG_CONFIG_PATH="$WORK/prefix/lib/pkgconfig"
export LD_LIBRARY_PATH="$WORK/prefix/lib"
export CXXFLAGS='-ffile-prefix-map=/work=. -ffile-prefix-map=/source=.'
RECONFIGURE=()
[[ ! -f "$WORK/build-app/meson-private/coredata.dat" ]] || RECONFIGURE=(--reconfigure)
meson setup "${RECONFIGURE[@]}" "$WORK/build-app" "$SOURCE" --prefix=/usr --buildtype=release \
  -Dportal=enabled -Dmutter=disabled -Db_ndebug=false
meson compile -C "$WORK/build-app" -j "${JOBS:-4}"
meson test -C "$WORK/build-app" --print-errorlogs
DESTDIR="$WORK/AppDir" meson install -C "$WORK/build-app" --no-rebuild
