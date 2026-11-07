#!/usr/bin/env bash
# Local, isolated ASan/UBSan regression suite. Never starts the real CLI, any
# listening socket, desktop portal/capture, GUI, or host input injection.
set -euo pipefail
cd "$(dirname "$0")/.."
export PKG_CONFIG_PATH="$PWD/.deps/sdbus/lib/pkgconfig${PKG_CONFIG_PATH:+:$PKG_CONFIG_PATH}"
export LD_LIBRARY_PATH="$PWD/.deps/sdbus/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
mkdir -p build-review
c++ -std=c++20 -g -O1 -fsanitize=address,undefined -fno-omit-frame-pointer -pthread \
  -Isrc/common -Isrc/streamer -DMETASHARE_HAVE_PORTAL -DMETASHARE_HAVE_MUTTER \
  $(pkg-config --cflags sdbus-c++ libpipewire-0.3 libavutil libavcodec libswscale libswresample openssl) \
  tests/review_lifecycle_test.cpp \
  src/streamer/{portal_pipewire_source,pipewire_audio_source,test_tone_source,test_pattern_source,audio_encoder,encoder,signaling_server,rtp_packetizer,rtp_server,discovery,mutter_screencast,mutter_virtual_source}.cpp \
  src/common/{signaling,secure_transport}.cpp \
  $(pkg-config --libs sdbus-c++ libpipewire-0.3 libavutil libavcodec libswscale libswresample openssl) \
  -o build-review/review-lifecycle-test
ASAN_OPTIONS=detect_leaks=1 timeout 20s build-review/review-lifecycle-test
c++ -std=c++20 -g -O1 -pthread -fsanitize=address,undefined -fno-omit-frame-pointer \
  -Isrc/common -Isrc/streamer tests/review_discovery_test.cpp src/streamer/discovery.cpp \
  -Wl,--wrap=socket,--wrap=bind,--wrap=setsockopt,--wrap=recvfrom,--wrap=sendto,--wrap=shutdown,--wrap=close \
  -o build-review/review-discovery-test
ASAN_OPTIONS=detect_leaks=1 timeout 20s build-review/review-discovery-test
