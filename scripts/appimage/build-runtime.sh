#!/usr/bin/env bash
# Run inside Alpine 3.21, never install these tools on the host.
set -euo pipefail
WORK=${WORK:-/work}
export SOURCE_DATE_EPOCH=${SOURCE_DATE_EPOCH:-1794096000}
# Exact toolchain/runtime package versions, as recorded for this release.
apk add --no-cache $(cat /source/scripts/appimage/alpine-build-packages.lock)
apk info -v | sort > "$WORK/runtime-packages.txt"
mkdir -p "$WORK/runtime-build"
tar xf "$WORK/sources/appimage-runtime-fedfad51.tar.gz" -C "$WORK/runtime-build" --strip-components=1
cd "$WORK/runtime-build"
printf 'https://github.com/AppImage/type2-runtime/commit/fedfad51acc2860b2916a1eef32ba4a500ff31ef\n' > src/runtime/version
# Preserve the upstream build implementation. Its two downloads have hardcoded
# hashes; we additionally retain those exact source archives beside this recipe.
# Do not install setuid helpers, create device nodes or configure services.
# Only the static FUSE library is needed for the runtime link.
sed -i 's/meson setup --prefix=\/usr ../meson setup --prefix=\/usr -Duseroot=false -Dutils=false -Dexamples=false -Dtests=false ../' scripts/common/install-dependencies.sh
bash scripts/common/install-dependencies.sh
bash scripts/build-runtime.sh
apk info -v | sort > "$WORK/runtime-packages.txt"
sha256sum out/runtime-x86_64 > "$WORK/runtime-sha256.txt"
