#!/usr/bin/env python3
"""Validates ast/projection/*.json against schemas/projection-map.schema.json.

Beyond the schema, checks two things it cannot express:

  * every ``mappings`` value and every ``elide`` entry must name a kind that exists in
    ast/treekind.json -- a typo or a stale kind name would otherwise silently never match and read
    as agreement;
  * a native node may not be both mapped and ignored, which is contradictory.

Run from the repository root.
"""

import glob
import json
import re
import sys

SCHEMA = "schemas/projection-map.schema.json"


def main():
    schema = json.load(open(SCHEMA))
    inventory = {k["name"] for k in json.load(open("ast/treekind.json"))["kinds"]}
    maps = sorted(glob.glob("ast/projection/*.json"))
    if not maps:
        print("OK: no projection maps to validate")
        return 0

    errors = []
    for path in maps:
        doc = json.load(open(path))
        for key in schema["required"]:
            if key not in doc:
                errors.append(f"{path}: missing required key '{key}'")
        for key in doc:
            if key not in schema["properties"]:
                errors.append(f"{path}: unexpected key '{key}'")

        sv = doc.get("schemaVersion")
        if not isinstance(sv, int) or sv < 1:
            errors.append(f"{path}.schemaVersion: expected integer >= 1, got {sv!r}")
        if not isinstance(doc.get("consumer", ""), str) or not doc.get("consumer"):
            errors.append(f"{path}.consumer: expected a non-empty string")

        mappings = doc.get("mappings", {})
        for native, canonical in sorted(mappings.items()):
            if canonical not in inventory:
                errors.append(
                    f"{path}.mappings[{native!r}]: {canonical!r} is not in ast/treekind.json"
                )
        for kind in sorted(doc.get("elide", [])):
            if kind not in inventory:
                errors.append(f"{path}.elide: {kind!r} is not in ast/treekind.json")

        both = sorted(set(mappings) & set(doc.get("ignored", [])))
        if both:
            errors.append(f"{path}: nodes both mapped and ignored: {both}")

        for native in sorted(doc.get("notes", {})):
            known = native in mappings or native in doc.get("ignored", []) or native in inventory
            if not known:
                errors.append(f"{path}.notes[{native!r}]: notes an unknown node")

    if errors:
        print("FATAL: projection map validation failed", file=sys.stderr)
        for e in errors:
            print(f"  {e}", file=sys.stderr)
        return 1

    total = sum(len(json.load(open(p)).get("mappings", {})) for p in maps)
    print(f"OK: {len(maps)} projection map(s) valid, {total} mappings, all targets in inventory")
    return 0


if __name__ == "__main__":
    sys.exit(main())
