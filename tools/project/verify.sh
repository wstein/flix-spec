#!/usr/bin/env bash
# Phase 0 spike verification, Gradle path: exercises the extractor end to
# end against the pinned, checksummed flix.jar and checks the properties
# the implementation plan calls non-negotiable for any generator (section
# 3.3): determinism (byte-identical across two runs) and an exact TreeKind
# count, not a floor. Stand-in for the fast tier (verify.yml) Phase 1
# formalizes; not itself CI.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

if [ ! -f .oracle/flix.jar ]; then
  echo "== fetching pinned oracle artifact =="
  ./tools/oracle/fetch.sh
fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

echo "== extracting fixtures/positive/hello.flix twice, checking determinism =="
./gradlew -q :tools:project:extract --args="fixtures/positive/hello.flix" > "$WORK/out1.json"
./gradlew -q :tools:project:extract --args="fixtures/positive/hello.flix" > "$WORK/out2.json"
diff "$WORK/out1.json" "$WORK/out2.json"
echo "OK: byte-identical across two runs"

echo "== extracting fixtures/negative/unclosed-paren.flix, checking ErrorTree recovery =="
./gradlew -q :tools:project:extract --args="fixtures/negative/unclosed-paren.flix" > "$WORK/broken.json"
grep -q '"kind":"ErrorTree"' "$WORK/broken.json"
echo "OK: parse error recovered into a well-formed tree with an ErrorTree node"

echo "== enumerating TreeKind via reflection, checking exact count =="
./gradlew -q :tools:project:listKinds > "$WORK/kinds.txt"
TOTAL="$(grep -m1 '^total:' "$WORK/kinds.txt" | cut -d' ' -f2)"
if [ "$TOTAL" != "192" ]; then
  echo "FATAL: expected 192 TreeKind leaves at this pin, got $TOTAL" >&2
  exit 1
fi
echo "OK: exactly 192 TreeKind leaves"

echo "== all Phase 0 spike checks passed =="
