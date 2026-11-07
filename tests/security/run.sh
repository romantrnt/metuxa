#!/bin/sh
set -eu
cd "$(dirname "$0")/../.."
work=$(mktemp -d)
server_pid=
trap 'if [ -n "$server_pid" ]; then kill "$server_pid" 2>/dev/null || true; fi; rm -rf "$work"' EXIT INT TERM
: "${CXX:=g++}"
: "${JAVAC:=javac}"
: "${JAVA:=java}"
$CXX -std=c++20 -Wall -Wextra -Werror -pthread ${SECURITY_TEST_FLAGS:-} -I src/common -I src/streamer \
    tests/security/security_tests.cpp src/common/secure_transport.cpp src/common/signaling.cpp src/streamer/signaling_server.cpp src/streamer/rtp_server.cpp src/streamer/rtp_packetizer.cpp \
    $(pkg-config --cflags --libs openssl) -o "$work/security-tests"
"$work/security-tests" > "$work/result"
cat "$work/result"
vector=$(sed -n 's/^VECTOR //p' "$work/result")
"$JAVAC" -d "$work" client/quest/app/src/main/java/com/metashare/client/SecureTransport.java tests/security/SecureTransportTest.java
"$work/security-tests" --interop-server > "$work/server" &
server_pid=$!
i=0
while [ ! -s "$work/server" ]; do i=$((i+1)); [ "$i" -lt 100 ] || exit 1; sleep .05; done
read -r port pin token < "$work/server"
"$JAVA" -cp "$work" com.metashare.client.SecureTransportTest "$vector" "$port" "$pin" "$token"
wait "$server_pid"
server_pid=
