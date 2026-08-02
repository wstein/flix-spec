#!/usr/bin/env bash
# Phase 1 verification: exercises extractor end-to-end against the pinned, checksummed
# flix.jar, verifies determinism (byte-identical across two forked JVMs), structural
# conformance to schemas/treekind.schema.json (required keys, types, patterns, enums),
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
python3 tools/project/validate-projection.py

echo "== regenerating fixtures/expected =="
./gradlew -q :tools:project:generateFixtures

echo "== validating regenerated fixtures/expected =="
python3 tools/project/validate-projection.py

echo "== regenerating ast/coverage.json =="
python3 tools/project/coverage.py

echo "== validating projection maps =="
python3 tools/project/validate-projection-map.py

echo "== conformance: expectations must agree with themselves =="
python3 tools/project/conformance.py --actual fixtures/expected

echo "== conformance: a mutated tree must be detected =="
# A comparison that cannot fail is not a check. Rename one kind and drop one child, then require a
# non-zero exit -- otherwise a silently no-op comparator would report every consumer as conforming.
MUT="$WORK/mutated"
cp -r fixtures/expected "$MUT"
python3 -c "$(printf '%s\n' \
  'import json, sys' \
  'p = sys.argv[1] + "/hello.json"' \
  'd = json.load(open(p))' \
  't = d["units"][0]["tree"]' \
  't["children"][1]["kind"] = "Expr.Binary"' \
  't["children"][1]["children"].pop()' \
  'json.dump(d, open(p, "w"))')" "$MUT"
if python3 tools/project/conformance.py --actual "$MUT" >/dev/null 2>&1; then
  echo "FATAL: conformance passed a deliberately mutated tree" >&2
  exit 1
fi
echo "OK: mutation detected"

echo "== validating committed ast/treekind.json against schema =="
python3 tools/project/validate-treekind.py

echo "== generating ast/treekind.json via reflection =="
./gradlew -q :tools:project:generateTreeKind

echo "== validating regenerated ast/treekind.json against schema =="
python3 tools/project/validate-treekind.py

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

echo "== all verification checks passed =="
