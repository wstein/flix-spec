#!/usr/bin/env bash
# Executable adapter example, not a parser: rename the reference's Root to ExampleRoot.
set -euo pipefail
SPEC="${1:?usage: identity-adapter.sh SPEC OUTPUT MAP}"
OUTPUT="${2:?output directory required}"
MAP="${3:?map path required}"
mkdir -p "$OUTPUT"
for source in "$SPEC"/fixtures/raw/*.json; do
  jq 'walk(if type == "object" and .kind? == "Root" then .kind = "ExampleRoot" else . end)' \
    "$source" > "$OUTPUT/$(basename "$source")"
done
jq -n --slurpfile inventory "$SPEC/ast/treekind.json" \
  --slurpfile transparency "$SPEC/ast/transparency.json" \
  --slurpfile unattachable "$SPEC/ast/unattachable.json" '
  ($transparency[0].treeKinds | map(select(.rule == "elide") | .name)) as $ignored |
  ($unattachable[0].treeKinds | map(.name)) as $unattachable |
  {schemaVersion: 1, consumer: "example-adapter",
   description: "Executable integration example; it transforms oracle output and is not an independent parser.",
   capabilities: ["structure", "tokens", "diagnostics", "recovery"],
   mappings: ($inventory[0].kinds | map(.name)
     | map(select(. as $k | ($ignored | index($k)) == null and ($unattachable | index($k)) == null))
     | map({key: (if . == "Root" then "ExampleRoot" else . end), value: .}) | from_entries),
   ignored: $ignored,
   dropWhenEmpty: ($transparency[0].treeKinds | map(select(.rule == "elide-empty") | .name)),
   recoveryMarkers: ($transparency[0].treeKinds | map(select(.recoveryMarker == true) | .name))
  }' > "$MAP"
