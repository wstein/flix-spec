#!/usr/bin/env bash
# Fetches the pinned oracle artifact (pin.json .oracleArtifact) into
# .oracle/flix.jar (gitignored) and verifies its SHA-256. This is the only
# network dependency of the fast tier: no compiler build, no mill, just a
# download and a digest check.
#
# Implementation plan section 4.1: flix-spec consumes a pinned, checksummed
# flix.jar and never compiles Flix. tools/project builds against this file.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PIN="$ROOT/pin.json"
DEST_DIR="$ROOT/.oracle"
DEST="$DEST_DIR/flix.jar"

URL="$(python3 -c "import json; print(json.load(open('$PIN'))['oracleArtifact']['url'])")"
EXPECT_SHA256="$(python3 -c "import json; print(json.load(open('$PIN'))['oracleArtifact']['sha256'])")"

mkdir -p "$DEST_DIR"

echo "Fetching $URL"
curl -sL -o "$DEST" "$URL"

ACTUAL_SHA256="$(shasum -a 256 "$DEST" | cut -d' ' -f1)"

if [ "$ACTUAL_SHA256" != "$EXPECT_SHA256" ]; then
  echo "FATAL: oracle artifact digest mismatch" >&2
  echo "  expected: $EXPECT_SHA256" >&2
  echo "  actual:   $ACTUAL_SHA256" >&2
  rm -f "$DEST"
  exit 1
fi

echo "Verified $DEST -- sha256 $ACTUAL_SHA256"
