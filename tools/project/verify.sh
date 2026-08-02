#!/usr/bin/env bash
# Phase 1 verification: exercises extractor end-to-end against the pinned, checksummed
# flix.jar, verifies determinism (byte-identical across two forked JVMs), structural
# conformance to schemas/{treekind,tokenkind,projection}.schema.json (required keys, types,
# patterns, enums), unit tests, and exact TreeKind/TokenKind counts matching pin.json.
#
# JSON field reads use jq (Category A); anything that walks a real JSON tree -- schema
# validation, coverage, conformance -- is a Gradle-invoked Scala tool in tools/project/src
# (Category B), not a Python script. Scripting on the JVM, never Python: implementation plan
# section 4.
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
# JavaExec forks a JVM per invocation, so these are two separate processes.
./gradlew -q :tools:project:extract --args="fixtures/positive/hello.flix" > "$WORK/out1.json"
./gradlew -q :tools:project:extract --args="fixtures/positive/hello.flix" > "$WORK/out2.json"
diff "$WORK/out1.json" "$WORK/out2.json"
echo "OK: byte-identical across two forked JVMs"

echo "== extracting fixtures/negative/unclosed-paren.flix, checking ErrorTree recovery =="
./gradlew -q :tools:project:extract --args="fixtures/negative/unclosed-paren.flix" > "$WORK/broken.json"
grep -q '"kind":"ErrorTree"' "$WORK/broken.json"
echo "OK: parse error recovered into a well-formed tree with an ErrorTree node"

echo "== validating committed fixtures/expected against schema and inventory =="
./gradlew -q :tools:project:validateProjection

echo "== regenerating fixtures/expected =="
./gradlew -q :tools:project:generateFixtures

echo "== validating regenerated fixtures/expected =="
./gradlew -q :tools:project:validateProjection

echo "== regenerating ast/coverage.json =="
./gradlew -q :tools:project:generateCoverage

echo "== losslessness: trees must reconstruct their source =="
# Oracle-free: compares each tree against its *input*, so unlike every other gate here it needs no
# independent specification and can say something about the reference itself. Catches dropped,
# duplicated or corrupted token text, none of which a structural comparison can see.
./gradlew -q :tools:project:lossless

echo "== validating projection maps =="
./gradlew -q :tools:project:validateProjectionMap

echo "== conformance: expectations must agree with themselves =="
./gradlew -q :tools:project:conformance --args="--actual fixtures/expected"

echo "== conformance: a mutated tree must be detected =="
# A comparison that cannot fail is not a check. Rename one kind and drop one child, then require a
# non-zero exit -- otherwise a silently no-op comparator would report every consumer as conforming.
MUT="$WORK/mutated"
cp -r fixtures/expected "$MUT"
jq '.units[0].tree.children[1].kind = "Expr.Binary" | .units[0].tree.children[1].children |= .[:-1]' \
  "$MUT/hello.json" > "$MUT/hello.json.tmp"
mv "$MUT/hello.json.tmp" "$MUT/hello.json"
if ./gradlew -q :tools:project:conformance --args="--actual $MUT" >/dev/null 2>&1; then
  echo "FATAL: conformance passed a deliberately mutated tree" >&2
  exit 1
fi
echo "OK: mutation detected"

echo "== validating committed ast/tokenkind.json against schema =="
./gradlew -q :tools:project:validateTokenKind

echo "== generating ast/tokenkind.json via reflection =="
./gradlew -q :tools:project:generateTokenKind

echo "== validating regenerated ast/tokenkind.json against schema =="
./gradlew -q :tools:project:validateTokenKind

echo "== validating committed ast/treekind.json against schema =="
./gradlew -q :tools:project:validateTreeKind

echo "== generating ast/treekind.json via reflection =="
./gradlew -q :tools:project:generateTreeKind

echo "== validating regenerated ast/treekind.json against schema =="
./gradlew -q :tools:project:validateTreeKind

echo "== validating ast/tokenkind.json structure and pin.json digest =="
TOK_EXPECT_COUNT="$(jq -r '.tokenKindCount' pin.json)"
TOK_EXPECT_DIGEST="$(jq -r '.tokenKindDigest' pin.json)"
TOK_ACTUAL_COUNT="$(jq -r '.tokenKindCount' ast/tokenkind.json)"
TOK_ACTUAL_DIGEST="$(jq -r '.tokenKindDigest' ast/tokenkind.json)"

if [ "$TOK_ACTUAL_COUNT" -ne "$TOK_EXPECT_COUNT" ]; then
  echo "FATAL: expected $TOK_EXPECT_COUNT TokenKind items, got $TOK_ACTUAL_COUNT" >&2
  exit 1
fi

if [ "$TOK_ACTUAL_DIGEST" != "$TOK_EXPECT_DIGEST" ]; then
  echo "FATAL: tokenKindDigest mismatch between pin.json and ast/tokenkind.json" >&2
  echo "  pin.json:        $TOK_EXPECT_DIGEST" >&2
  echo "  tokenkind.json:  $TOK_ACTUAL_DIGEST" >&2
  exit 1
fi
echo "OK: ast/tokenkind.json carries exactly $TOK_ACTUAL_COUNT kinds matching pin digest $TOK_ACTUAL_DIGEST"

echo "== validating ast/treekind.json structure and pin.json digest =="
EXPECT_COUNT="$(jq -r '.treeKindCount' pin.json)"
EXPECT_DIGEST="$(jq -r '.treeKindDigest' pin.json)"

ACTUAL_COUNT="$(jq -r '.treeKindCount' ast/treekind.json)"
ACTUAL_DIGEST="$(jq -r '.treeKindDigest' ast/treekind.json)"

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

echo "== all verification checks passed =="
