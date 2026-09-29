#!/usr/bin/env bash
set -euo pipefail
SPEC="${1:?usage: produce.sh SPEC OUTPUT}"
OUTPUT="${2:?output directory required}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SPEC="$(cd "$SPEC" && pwd)"
# Refuse reuse: an adapter that stops emitting a fixture must not inherit stale output.
if [ -e "$OUTPUT" ]; then
  echo "FATAL: output already exists; choose a fresh directory: $OUTPUT" >&2
  exit 2
fi
mkdir -p "$OUTPUT"
for expected in "$SPEC"/fixtures/expected/*.json; do
  source="$(jq -er 'if (.units | length) == 1 then .units[0].source else error("expected one unit") end' "$expected")"
  target="$OUTPUT/$(basename "$expected")"
  bash "$HERE/adapter.sh" "$SPEC/$source" "$source" > "$target.tmp"
  jq -e --arg source "$source" \
    '.schemaVersion == 2 and .form == "raw" and (.units | length) == 1 and .units[0].source == $source' \
    "$target.tmp" >/dev/null
  mv "$target.tmp" "$target"
done
