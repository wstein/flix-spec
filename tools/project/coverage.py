#!/usr/bin/env python3
"""Generates ast/coverage.json: which TreeKinds the fixture suite exercises.

Implementation plan section 7, Phase 2: fixture coverage must be a measurable artifact rather
than a claim. A kind is *covered* when at least one committed expectation contains a node of that
kind.

An uncovered kind is not automatically a gap. Some kinds are only reachable from inputs no
fixture yet exercises; others may be unreachable from any input at all, which is a fact about
Flix rather than about this suite. Distinguishing the two needs the corpus-wide reachability run,
so this file reports coverage and deliberately does not editorialise about the remainder.

Run from the repository root.
"""

import glob
import json
import sys
from collections import Counter


def walk(node, counter):
    if "kind" not in node:  # token leaf
        return
    counter[node["kind"]] += 1
    for child in node.get("children", []):
        walk(child, counter)


def main():
    inventory = json.load(open("ast/treekind.json"))
    all_kinds = [k["name"] for k in inventory["kinds"]]

    counts = Counter()
    fixtures = sorted(glob.glob("fixtures/expected/*.json"))
    if not fixtures:
        print("FATAL: no expectations in fixtures/expected/", file=sys.stderr)
        return 1

    by_kind_files = {}
    for f in fixtures:
        doc = json.load(open(f))
        local = Counter()
        for unit in doc["units"]:
            walk(unit["tree"], local)
        counts.update(local)
        for k in local:
            by_kind_files.setdefault(k, []).append(doc["units"][0]["source"])

    covered = sorted(k for k in all_kinds if counts[k] > 0)
    uncovered = sorted(k for k in all_kinds if counts[k] == 0)

    unknown = sorted(set(counts) - set(all_kinds))
    if unknown:
        print(f"FATAL: kinds not in inventory: {unknown}", file=sys.stderr)
        return 1

    out = {
        "schemaVersion": 1,
        "generatedBy": "tools/project/coverage.py",
        "upstreamCommit": inventory["upstreamCommit"],
        "oracleSha256": inventory["oracleSha256"],
        "treeKindCount": len(all_kinds),
        "coveredCount": len(covered),
        "uncoveredCount": len(uncovered),
        "fixtureCount": len(fixtures),
        "covered": {k: counts[k] for k in covered},
        "uncovered": uncovered,
    }
    with open("ast/coverage.json", "w") as fh:
        json.dump(out, fh, indent=2)
        fh.write("\n")

    pct = 100.0 * len(covered) / len(all_kinds)
    print(
        f"Wrote ast/coverage.json: {len(covered)}/{len(all_kinds)} kinds covered "
        f"({pct:.1f}%) by {len(fixtures)} fixtures; {len(uncovered)} uncovered"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
