#!/usr/bin/env bash
# Phase 1 verification: exercises extractor end-to-end against the pinned, checksummed
# flix.jar, verifies determinism (byte-identical across two runs), schema validity,
# unit tests, and exact TreeKind count matching pin.json.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

if [ ! -f .oracle/flix.jar ]; then
  echo "== fetching pinned oracle artifact =="
  ./tools/oracle/fetch.sh
fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

echo "== running Gradle test suite =="
./gradlew test

echo "== extracting fixtures/positive/hello.flix twice, checking determinism =="
./gradlew -q :tools:project:extract --args="fixtures/positive/hello.flix" > "$WORK/out1.json"
./gradlew -q :tools:project:extract --args="fixtures/positive/hello.flix" > "$WORK/out2.json"
diff "$WORK/out1.json" "$WORK/out2.json"
echo "OK: byte-identical across two runs"

echo "== extracting fixtures/negative/unclosed-paren.flix, checking ErrorTree recovery =="
./gradlew -q :tools:project:extract --args="fixtures/negative/unclosed-paren.flix" > "$WORK/broken.json"
grep -q '"kind":"ErrorTree"' "$WORK/broken.json"
echo "OK: parse error recovered into a well-formed tree with an ErrorTree node"

echo "== generating ast/treekind.json via reflection =="
./gradlew -q :tools:project:generateTreeKind

echo "== validating ast/treekind.json structure and pin.json digest =="
EXPECT_COUNT="$(python3 -c "import json; print(json.load(open('pin.json'))['treeKindCount'])")"
EXPECT_DIGEST="$(python3 -c "import json; print(json.load(open('pin.json'))['treeKindDigest'])")"

ACTUAL_COUNT="$(python3 -c "import json; print(json.load(open('ast/treekind.json'))['treeKindCount'])")"
ACTUAL_DIGEST="$(python3 -c "import json; print(json.load(open('ast/treekind.json'))['treeKindDigest'])")"

if [ "$ACTUAL_COUNT" -ne "$EXPECT_COUNT" ]; then
  echo "FATAL: expected $EXPECT_COUNT TreeKind items, got $ACTUAL_COUNT" >&2
  exit 1
fi

if [ "$ACTUAL_DIGEST" != "$EXPECT_DIGEST" ]; then
  echo "FATAL: treeKindDigest mismatch between pin.json and ast/treekind.json" >&2
  echo "  pin.json:       $EXPECT_DIGEST" >&2
  echo "  treekind.json:  $ACTUAL_DIGEST" >&2
  exit 1
fi
echo "OK: ast/treekind.json carries exactly $ACTUAL_COUNT kinds matching pin digest $ACTUAL_DIGEST"

echo "== all Phase 1 verification checks passed =="
