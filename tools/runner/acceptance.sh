#!/usr/bin/env bash
# Resolves files from a local Maven publication, then tests them outside the checkout.
set -euo pipefail
REPO="${1:?usage: acceptance.sh MAVEN_REPOSITORY VERSION}"
VERSION="${2:?version required}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
REPO="$(cd "$REPO" && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
cp "$REPO/io/github/wstein/flix-spec-runner/$VERSION/flix-spec-runner-$VERSION.jar" "$WORK/runner.jar"
cp "$REPO/io/github/wstein/flix-spec/$VERSION/flix-spec-$VERSION.jar" "$WORK/spec.jar"
cp "$ROOT/examples/consumer/identity-adapter.sh" "$WORK/adapter.sh"
cd "$WORK"
mkdir spec
( cd spec && jar xf ../spec.jar )
if jar tf runner.jar | grep -q '^ca/uwaterloo/'; then
  echo "FATAL: runner contains oracle classes" >&2
  exit 1
fi
java -jar runner.jar --supported-schemas > supported.json
jq -e '.projection == 2 and (.["conformance-report"] | type == "number")' supported.json >/dev/null
REPORT_VERSION="$(jq -r '.["conformance-report"]' supported.json)"
bash adapter.sh "$WORK/spec" actual map.json

expect_exit() {
  local expected="$1"
  shift
  local status=0
  "$@" > invocation.log 2>&1 || status=$?
  if [ "$status" -ne "$expected" ]; then
    cat invocation.log >&2
    echo "FATAL: expected exit $expected, got $status" >&2
    exit 1
  fi
}
run() {
  java -jar runner.jar --spec-root "$WORK/spec" --source-root "$WORK/spec" \
    --actual "$WORK/actual" --map "$WORK/map.json" --report "$WORK/report.json" "$@"
}
expect_exit 0 run
jq -e '[.lanes[].verdict] | all(. == "pass")' report.json >/dev/null
jq -e --argjson version "$REPORT_VERSION" '.schemaVersion == $version and
  any(.lanes.source_invariants.checks[]; .id == "lexical-correctness" and .verdict == "not-applicable")' report.json >/dev/null
expect_exit 0 java -jar runner.jar render --report report.json --html report.html
grep -q 'oracle_conformance: pass' report.html

# An unknown diagnostic must not disable the known diagnostic's wrong-line check.
cp actual/trailing-dot.json original-diagnostic.json
jq '.units[0].diagnostics[0].line += 1 |
    .units[0].diagnostics += [{kind:"ConsumerOnly",line:1,col:1,message:"unmapped"}]' \
  original-diagnostic.json > actual/trailing-dot.json
expect_exit 1 run
jq -e 'any(.lanes.diagnostic_conformance.divergences[]; .reason == "diagnostic")' report.json >/dev/null
expect_exit 0 java -jar runner.jar render --report report.json --html diagnostic-failure.html
grep -q 'Partial diagnostic comparability' diagnostic-failure.html
cp original-diagnostic.json actual/trailing-dot.json

# A real structural mutation, with an otherwise valid document, must fail.
cp actual/hello.json original.json
jq '.units[0].tree.children[0].kind = "Expr.Binary"' original.json > actual/hello.json
expect_exit 1 run
jq -e '.lanes.oracle_conformance.divergenceCount > 0' report.json >/dev/null
expect_exit 0 java -jar runner.jar render --report report.json --html failure.html
grep -q 'oracle_conformance: fail' failure.html
cp original.json actual/hello.json

# Explicit capabilities prohibit silence; each failure is named, not a crash.
cp map.json original-map.json
for capability in diagnostics tokens recovery; do
  bash adapter.sh "$WORK/spec" actual map.json
  case "$capability" in
    diagnostics)
      for f in actual/*.json; do
        jq '.units[].diagnostics = []' "$f" > edit.json
        mv edit.json "$f"
      done ;;
    tokens)
      for f in actual/*.json; do
        jq 'walk(if type == "object" and has("children") then .children |= map(select(has("token") | not)) else . end)' \
          "$f" > edit.json
        mv edit.json "$f"
      done ;;
    recovery) jq '.recoveryMarkers = []' original-map.json > map.json ;;
  esac
  expect_exit 1 run
  grep -q "$capability" invocation.log
done
bash adapter.sh "$WORK/spec" actual map.json

jq '.schemaVersion = 999' original.json > actual/hello.json
expect_exit 2 run
grep -q 'unsupported.*schemaVersion' invocation.log
cp original.json actual/hello.json
expect_exit 2 java -jar runner.jar --actual
expect_exit 2 java -jar runner.jar --spec-root missing --actual actual
expect_exit 0 java -jar runner.jar init --directory starter --consumer test-consumer
expect_exit 2 java -jar runner.jar init --directory starter --consumer another
expect_exit 2 bash starter/produce.sh "$WORK/spec" "$WORK/unimplemented-output"
grep -q 'TODO: implement' invocation.log
expect_exit 2 bash starter/produce.sh "$WORK/spec" "$WORK/unimplemented-output"
grep -q 'output already exists' invocation.log
echo 'OK: published runner passes all four lanes and rejects mutations, missing capabilities and incompatible input outside the checkout'
