#!/bin/sh
set -eu
cd "$(dirname "$0")/../.."
work=$(mktemp -d)
tests=
trap 'if [ -n "$tests" ]; then kill "$tests" 2>/dev/null || true; wait "$tests" 2>/dev/null || true; fi; rm -rf "$work"' EXIT INT TERM
: "${CXX:=g++}" "${JAVAC:=javac}" "${JAVA:=java}"
$CXX -std=c++20 -Wall -Wextra -Werror -pthread ${SECURITY_TEST_FLAGS:-} -I src/common -I src/streamer \
    tests/security/pairing_tests.cpp src/common/secure_transport.cpp src/common/signaling.cpp src/streamer/signaling_server.cpp \
    $(pkg-config --cflags --libs openssl) -o "$work/pairing-tests"
"$work/pairing-tests" &
tests=$!
"$JAVAC" -d "$work" client/quest/app/src/main/java/com/metashare/client/SecureTransport.java \
    client/quest/app/src/main/java/com/metashare/client/PairingCode.java client/quest/app/src/main/java/com/metashare/client/PairingClient.java tests/security/PairingClientTest.java
"$JAVA" -cp "$work" com.metashare.client.PairingClientTest "$work/pairing-tests"
wait "$tests"
tests=
