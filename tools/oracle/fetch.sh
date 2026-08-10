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
# This has a precedent worth remembering: a committed lexicon in this ecosystem was once provenanced
# to a fork rather than to the pin, and text-scraping is not what let that happen -- the absence of
# this check is.
UPSTREAM="https://github.com/flix/flix/releases/download/"
if [ "${URL#"$UPSTREAM"}" = "$URL" ]; then
  echo "FATAL: pin.json names an oracle artifact that is not an upstream flix/flix release" >&2
  echo "  url:      $URL" >&2
  echo "  required: ${UPSTREAM}<tag>/flix.jar" >&2
  echo "" >&2
  echo "The oracle must always be flix/flix. A fork may be a fine thing to test against, but it is" >&2
  echo "not what this repository derives its fixtures from, and a digest check cannot tell them apart." >&2
  exit 1
fi

# ...and it must be the release the pin actually names, not merely some upstream release. Bumping
# `upstream.tag` while leaving the artifact URL behind would download the previous compiler and
# verify it against the previous digest, reporting success the whole way.
PIN_TAG="$(jq -r '.upstream.tag' "$PIN")"
URL_TAG="${URL#"$UPSTREAM"}"
URL_TAG="${URL_TAG%%/*}"
if [ "$URL_TAG" != "$PIN_TAG" ]; then
  echo "FATAL: the oracle artifact URL and the pinned tag disagree" >&2
  echo "  upstream.tag:       $PIN_TAG" >&2
  echo "  url names release:  $URL_TAG" >&2
  exit 1
fi

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
