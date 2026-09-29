#!/usr/bin/env bash
set -euo pipefail
RUNNER="${1:?usage: check.sh RUNNER_JAR SPEC OUTPUT [SOURCE_ROOT]}"
SPEC="${2:?spec root required}"
OUTPUT="${3:?adapter output required}"
SOURCE_ROOT="${4:-$SPEC}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPORTS="$(mktemp -d "${TMPDIR:-/tmp}/flix-spec-reports.XXXXXX")"
echo "Reports: $REPORTS"
status=0
java -jar "$RUNNER" --spec-root "$SPEC" --source-root "$SOURCE_ROOT" \
  --actual "$OUTPUT" --map "$HERE/projection-map.json" --report "$REPORTS/report.json" || status=$?
# Render on conformance failure too, but never render a previous run's report.
if [ -f "$REPORTS/report.json" ]; then
  java -jar "$RUNNER" render --report "$REPORTS/report.json" --html "$REPORTS/report.html"
fi
exit "$status"
