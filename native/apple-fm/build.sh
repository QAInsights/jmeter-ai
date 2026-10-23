#!/bin/sh
set -eu

ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)"
SOURCE="$ROOT/native/apple-fm/FeatherWandAFM.swift"
OUTPUT="$ROOT/src/main/resources/org/qainsights/jmeter/ai/native/macos-arm64/feather-wand-afm"

mkdir -p "$(dirname "$OUTPUT")"
xcrun swiftc -O -parse-as-library -target arm64-apple-macos26.0 "$SOURCE" -o "$OUTPUT"
codesign -s - --force "$OUTPUT"
