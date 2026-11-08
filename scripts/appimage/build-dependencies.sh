#!/usr/bin/env bash
# Run inside the Ubuntu 24.04 release container. Inputs are checksum-verified archives.
set -euo pipefail
WORK=${WORK:-/work}
JOBS=${JOBS:-4}
PREFIX="$WORK/prefix"
export SOURCE_DATE_EPOCH=${SOURCE_DATE_EPOCH:-1794096000}
export CFLAGS='-O2 -g0 -ffile-prefix-map=/work=.'
export CXXFLAGS="$CFLAGS"
export PKG_CONFIG_PATH="$PREFIX/lib/pkgconfig"
export LD_LIBRARY_PATH="$PREFIX/lib"
mkdir -p "$WORK/build-deps" "$PREFIX"
extract() { mkdir -p "$WORK/build-deps/$1"; tar xf "$WORK/sources/$2" -C "$WORK/build-deps/$1"; }
if [[ ! -f "$PREFIX/lib/pkgconfig/x264.pc" ]]; then
  extract x264 x264-b35605ac.tar.gz
  (cd "$WORK/build-deps/x264"; ./configure --prefix="$PREFIX" --enable-shared --disable-cli --enable-pic --disable-opencl; make -j"$JOBS"; make install)
fi
if [[ ! -f "$PREFIX/lib/pkgconfig/ffnvcodec.pc" ]]; then
  extract nv-codec-headers nv-codec-headers-157becbf.tar.gz
  make -C "$WORK/build-deps/nv-codec-headers" PREFIX="$PREFIX" install
fi
if [[ ! -f "$PREFIX/lib/pkgconfig/libavcodec.pc" ]]; then
  extract ffmpeg ffmpeg-3a0867c2.tar.gz
  (cd "$WORK/build-deps/ffmpeg"; ./configure --prefix="$PREFIX" \
    --disable-static --enable-shared --enable-pic --disable-programs --disable-doc \
    --disable-everything --disable-autodetect --disable-network \
    --disable-avformat --disable-avdevice --disable-avfilter --disable-postproc \
    --enable-gpl --enable-version3 --enable-libx264 --enable-libopus \
    --enable-encoder=libx264,libopus,h264_nvenc,hevc_nvenc,h264_vaapi,hevc_vaapi \
    --enable-vaapi --enable-libdrm --enable-ffnvcodec --enable-nvenc \
    --extra-cflags="$CFLAGS"; make -j"$JOBS"; make install)
fi
if [[ ! -f "$PREFIX/lib/pkgconfig/sdbus-c++.pc" ]]; then
  extract sdbus-cpp sdbus-cpp-30d9f1d4.tar.gz
  cmake -S "$WORK/build-deps/sdbus-cpp" -B "$WORK/build-deps/sdbus-build" -G Ninja \
    -DCMAKE_INSTALL_PREFIX="$PREFIX" -DCMAKE_BUILD_TYPE=Release \
    -DBUILD_CODE_GEN=OFF -DBUILD_DOC=OFF -DBUILD_TESTS=OFF
  cmake --build "$WORK/build-deps/sdbus-build" -j "$JOBS"
  cmake --install "$WORK/build-deps/sdbus-build"
fi
