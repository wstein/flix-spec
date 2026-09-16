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

URL="$(jq -r '.oracleArtifact.url' "$PIN")"
EXPECT_SHA256="$(jq -r '.oracleArtifact.sha256' "$PIN")"

# The oracle is flix/flix, always, and a digest check cannot say so. A fork's jar hashes to its own
# digest perfectly happily, so `pin.json` naming one would sail through every gate below and every
# gate downstream -- the fixtures would simply describe a different compiler, and nothing in this
# repository would contradict them. Assert the source, not just the bytes.
#
# Constructed and compared for equality, never pattern-matched. A prefix test is not a URL test:
# curl applies RFC 3986 remove_dot_segments before it resolves, so
# `https://github.com/flix/flix/releases/download/../../elsewhere/x.jar` passes a `${URL#prefix}`
# check and fetches from `elsewhere`. Deriving the only URL this pin can legitimately name removes
# the whole class -- there is nothing left to smuggle past, and it subsumes the tag check too, since
# a tag bumped without the URL no longer produces a value that can match.
PIN_TAG="$(jq -r '.upstream.tag' "$PIN")"
EXPECT_URL="https://github.com/flix/flix/releases/download/${PIN_TAG}/flix.jar"

if [ "$URL" != "$EXPECT_URL" ]; then
  echo "FATAL: pin.json's oracle artifact is not the upstream release the pin names" >&2
  echo "  pin.json names: $URL" >&2
  echo "  required:       $EXPECT_URL" >&2
  echo "" >&2
  echo "The oracle must always be flix/flix, at exactly upstream.tag. A fork may be a fine thing to" >&2
  echo "test against, but it is not what this repository derives its fixtures from, and neither a" >&2
  echo "digest check nor a prefix match can tell them apart." >&2
  exit 1
fi

mkdir -p "$DEST_DIR"

# A cached jar is usable only after checking it against the current pin.
if [ -f "$DEST" ] && [ "$(shasum -a 256 "$DEST" | cut -d' ' -f1)" = "$EXPECT_SHA256" ]; then
  echo "Verified cached $DEST -- sha256 $EXPECT_SHA256"
  exit 0
fi

# Publish only a complete, verified download. A failed fetch must not destroy a
# previously installed oracle or expose a partial jar to another Gradle process.
DOWNLOAD="$(mktemp "$DEST_DIR/flix.jar.download.XXXXXX")"
trap 'rm -f "$DOWNLOAD"' EXIT

echo "Fetching $URL"
# -f: without it curl exits 0 on 4xx/5xx and writes the error body to flix.jar, so the digest check
# below reports "digest mismatch" for what is actually a 404 -- precisely the wrong diagnosis for the
# most likely mistake, bumping the pin before the release asset exists.
# --path-as-is: belt and braces. The URL is already exact, but this guarantees curl resolves the
# path this script vetted rather than a normalisation of it.
if ! curl -fsSL --path-as-is -o "$DOWNLOAD" "$URL"; then
  echo "FATAL: could not download the oracle artifact from $URL" >&2
  exit 1
fi

ACTUAL_SHA256="$(shasum -a 256 "$DOWNLOAD" | cut -d' ' -f1)"

if [ "$ACTUAL_SHA256" != "$EXPECT_SHA256" ]; then
  echo "FATAL: oracle artifact digest mismatch" >&2
  echo "  expected: $EXPECT_SHA256" >&2
  echo "  actual:   $ACTUAL_SHA256" >&2
  exit 1
fi

mv "$DOWNLOAD" "$DEST"
echo "Verified $DEST -- sha256 $ACTUAL_SHA256"
