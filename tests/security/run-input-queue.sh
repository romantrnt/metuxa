#!/bin/sh
set -eu
cd "$(dirname "$0")/../.."
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT INT TERM
: "${JAVAC:=javac}"
: "${JAVA:=java}"
"$JAVAC" -Xlint:all -Werror -d "$work" \
    client/quest/app/src/main/java/com/metashare/client/SessionInputQueue.java \
    tests/security/SessionInputQueueTest.java
"$JAVA" -ea -cp "$work" com.metashare.client.SessionInputQueueTest
