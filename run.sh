#!/usr/bin/env bash
# Build & run:  ./run.sh [port] [floors]
set -e
cd "$(dirname "$0")"
mkdir -p build
javac -encoding UTF-8 -cp "lib/*" -d build $(find src -name "*.java")
java -cp "build:lib/*" com.parking.Main "${1:-8080}" "${2:-3}"
