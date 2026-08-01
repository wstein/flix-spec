#!/usr/bin/env python3
"""Structural validation of ast/treekind.json against schemas/treekind.schema.json.

Covers the subset of JSON Schema the file actually uses -- required keys, types, patterns,
enums, minimum/minLength, additionalProperties, and $ref'd array items -- plus a cross-check
that treeKindCount agrees with len(kinds) rather than only with itself. Dependency-free on
purpose: jsonschema is not available and is not worth pinning for this.

Run from the repository root.
"""
import json, re, sys

schema = json.load(open("schemas/treekind.schema.json"))
doc = json.load(open("ast/treekind.json"))
errors = []

def check(obj, sch, path):
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
        if "enum" in spec and val not in spec["enum"]:
            errors.append(f"{p}: {val!r} not in {spec['enum']}")
        if t == "array" and isinstance(val, list) and "$ref" in spec.get("items", {}):
            ref = spec["items"]["$ref"].split("/")[-1]
            item_schema = schema["definitions"][ref]
            for i, item in enumerate(val):
                check(item, item_schema, f"{p}[{i}]")

check(doc, schema, "treekind.json")

# Cross-check the two count fields agree with reality rather than with each other only.
if doc.get("treeKindCount") != len(doc.get("kinds", [])):
    errors.append(
        f"treeKindCount {doc.get('treeKindCount')} != len(kinds) {len(doc.get('kinds', []))}"
    )

if errors:
    print("FATAL: schema validation failed", file=sys.stderr)
    for e in errors:
        print(f"  {e}", file=sys.stderr)
    sys.exit(1)
print(f"OK: conforms to treekind.schema.json ({len(doc['kinds'])} kinds validated)")
