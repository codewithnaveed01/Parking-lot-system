#!/usr/bin/env bash
# Build & run: ./run.sh [port]  (set ADMIN_PASS and GUARD_PASS before public deployment)
set -e
cd "$(dirname "$0")"
mkdir -p build
find src -name '*.java' | sort > build/sources.txt
javac -encoding UTF-8 -cp 'lib/*' -d build @build/sources.txt
java -cp 'build:lib/*' com.parking.Main "${1:-${PORT:-8080}}"
