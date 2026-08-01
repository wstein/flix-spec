#!/usr/bin/env python3
"""Structural validation of fixtures/expected/*.json against schemas/projection.schema.json.

Two checks the schema alone cannot express:

  * every ``kind`` must exist in ``ast/treekind.json``. The schema can only say "a string", but a
    projected tree whose vocabulary has drifted from the inventory is exactly the failure this
    repository exists to catch;
  * ``source`` must be repository-relative. An absolute path would make the committed expectation
    machine-specific and fail the diff gate on any other checkout.

Dependency-free on purpose: jsonschema is not available and is not worth pinning for this.
Run from the repository root.
"""

import glob
import json
import re
import sys

schema = json.load(open("schemas/projection.schema.json"))
inventory = {k["name"] for k in json.load(open("ast/treekind.json"))["kinds"]}
errors = []


def check(obj, sch, path):
    """Validate ``obj`` against the subset of JSON Schema these files use."""
    if "$ref" in sch:
        sch = schema["definitions"][sch["$ref"].split("/")[-1]]

    props = sch.get("properties", {})
    for key in sch.get("required", []):
        if key not in obj:
            errors.append(f"{path}: missing required key '{key}'")
    if sch.get("additionalProperties") is False:
        for key in obj:
            if key not in props:
                errors.append(f"{path}: unexpected key '{key}'")

    for key, spec in props.items():
        if key not in obj:
            continue
        val, p = obj[key], f"{path}.{key}"
        if "$ref" in spec:
            check(val, spec, p)
            continue
        t = spec.get("type")
        if t == "integer" and not isinstance(val, int):
            errors.append(f"{p}: expected integer, got {type(val).__name__}")
        if t == "string" and not isinstance(val, str):
            errors.append(f"{p}: expected string, got {type(val).__name__}")
        if t == "array" and not isinstance(val, list):
            errors.append(f"{p}: expected array, got {type(val).__name__}")
        if "minimum" in spec and isinstance(val, int) and val < spec["minimum"]:
            errors.append(f"{p}: {val} < minimum {spec['minimum']}")
        if "minLength" in spec and isinstance(val, str) and len(val) < spec["minLength"]:
            errors.append(f"{p}: shorter than minLength {spec['minLength']}")
        if "pattern" in spec and isinstance(val, str) and not re.match(spec["pattern"], val):
            errors.append(f"{p}: {val!r} does not match {spec['pattern']}")
        if t == "array" and isinstance(val, list) and "$ref" in spec.get("items", {}):
            for i, item in enumerate(val):
                check(item, spec["items"], f"{p}[{i}]")


def walk(node, path, kinds_seen):
    """Recurse a projected tree, collecting kinds and validating node shape."""
    if "kind" not in node:  # token leaf
        for key in ("token", "text", "start", "end"):
            if key not in node:
                errors.append(f"{path}: token node missing '{key}'")
        return
    kind = node["kind"]
    kinds_seen.add(kind)
    if kind not in inventory:
        errors.append(f"{path}: kind {kind!r} is not in ast/treekind.json")
    for i, child in enumerate(node.get("children", [])):
        walk(child, f"{path}.children[{i}]", kinds_seen)


def main():
    files = sorted(glob.glob("fixtures/expected/*.json"))
    if not files:
        print("FATAL: no expectations found in fixtures/expected/", file=sys.stderr)
        return 1

    all_kinds = set()
    for f in files:
        doc = json.load(open(f))
        check(doc, schema, f)
        for i, unit in enumerate(doc.get("units", [])):
            p = f"{f}.units[{i}]"
            src = unit.get("source", "")
            if src.startswith("/") or re.match(r"^[A-Za-z]:", src):
                errors.append(f"{p}.source: {src!r} is absolute; must be repository-relative")
            for j, d in enumerate(unit.get("diagnostics", [])):
                if "/Users/" in d.get("message", "") or "/home/" in d.get("message", ""):
                    errors.append(f"{p}.diagnostics[{j}].message: contains an absolute path")
            if "tree" in unit:
                walk(unit["tree"], f"{p}.tree", all_kinds)

    if errors:
        print("FATAL: projection validation failed", file=sys.stderr)
        for e in errors:
            print(f"  {e}", file=sys.stderr)
        return 1

    print(
        f"OK: {len(files)} expectation(s) conform to projection.schema.json; "
        f"{len(all_kinds)} distinct kinds, all present in ast/treekind.json"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
