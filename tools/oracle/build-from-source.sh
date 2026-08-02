#!/usr/bin/env bash
# Fallback for a pin whose release does not ship a flix.jar asset (Phase 0
# Q2, "no" branch). Builds the assembly jar from a throwaway checkout at the
# pinned commit -- mill appears here and nowhere else in flix-spec. This
# script does NOT update pin.json: recording a self-built digest as the
# oracle is a human decision (see docs/PIN-BUMP.md), not an automatic one --
# a self-built jar was empirically NOT byte-identical to the v0.75.1 release
# asset (16794 vs 16776 entries; see docs/phase0-spike.md, "Q2"), so the
# resulting digest is provenance for THIS build only, not a substitute for
# an upstream-published one.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PIN="$ROOT/pin.json"

COMMIT="$(jq -r '.upstream.commit' "$PIN")"
TAG="$(jq -r '.upstream.tag' "$PIN")"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

git clone --quiet "https://github.com/flix/flix" "$WORK/flix"
git -C "$WORK/flix" checkout --quiet "$TAG"

ACTUAL_COMMIT="$(git -C "$WORK/flix" rev-parse HEAD)"
if [ "$ACTUAL_COMMIT" != "$COMMIT" ]; then
  echo "FATAL: tag $TAG resolved to $ACTUAL_COMMIT, expected $COMMIT" >&2
  exit 1
fi

(cd "$WORK/flix" && ./mill flix.assembly)

JAR="$(find "$WORK/flix/out/flix/assembly.dest" -name '*.jar' | head -1)"
DIGEST="$(shasum -a 256 "$JAR" | cut -d' ' -f1)"

mkdir -p "$ROOT/.oracle"
cp "$JAR" "$ROOT/.oracle/flix.jar"

echo "Built $ROOT/.oracle/flix.jar -- sha256 $DIGEST"
echo "This digest is NOT recorded in pin.json automatically."
echo "If you intend to use it as the oracle, update pin.json.oracleArtifact by hand"
echo "and note in the pin-bump PR that it is a self-built artifact, not an upstream release asset."
