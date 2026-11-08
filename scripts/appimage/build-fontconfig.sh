#!/usr/bin/env bash
# Exact Ubuntu source and distro patches, plus one relocatable-template patch.
set -euo pipefail
WORK=${WORK:-/work}
SOURCE=${SOURCE:-/source}
export SOURCE_DATE_EPOCH=${SOURCE_DATE_EPOCH:-1794096000}
mkdir -p "$WORK/sources/ubuntu" "$WORK/build-deps"
if [[ ! -f "$WORK/sources/ubuntu/fontconfig_2.15.0-1.1ubuntu2.dsc" ]]; then
  (cd "$WORK/sources/ubuntu"; apt-get source --download-only --yes fontconfig=2.15.0-1.1ubuntu2)
fi
if [[ ! -d "$WORK/build-deps/fontconfig" ]]; then
  dpkg-source -x "$WORK/sources/ubuntu/fontconfig_2.15.0-1.1ubuntu2.dsc" "$WORK/build-deps/fontconfig"
  patch -d "$WORK/build-deps/fontconfig" -p1 < "$SOURCE/scripts/appimage/fontconfig-template-dir.patch"
fi
cd "$WORK/build-deps/fontconfig"
CFLAGS='-O2 -g0 -ffile-prefix-map=/work=.' ./configure --prefix="$WORK/prefix" \
  --enable-shared --disable-static --disable-docs --disable-cache-build \
  --with-default-fonts=/usr/share/fonts --with-add-fonts=/usr/local/share/fonts
make -j"${JOBS:-4}"
make install
