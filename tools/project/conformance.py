#!/usr/bin/env python3
"""Compares a consumer parser's projected trees against fixtures/expected/.

This is the shared half of the conformance check (implementation plan section 7, Phase 3). Each
consumer produces canonical projected trees for the fixtures; the comparison itself lives here so
that four repositories do not re-derive it four times, which is the duplication this repository
exists to end.

What is compared, per docs/PROJECTION.md section 3:

  * node kind, child order and nesting are **load-bearing** and gated;
  * spans and tokens are **not** compared. Token vocabularies differ legitimately between parsers
    and spans are advisory, so including either would report differences that are not
    disagreements about structure.

A consumer whose trees already carry canonical TreeKind names needs no projection map. One that
uses its own vocabulary supplies ast/projection/<consumer>.json; unmapped native names are counted
separately from divergences, because "we have not mapped this yet" is a different fact from "we
disagree with the reference".

Usage:
    conformance.py --actual <dir> [--map <file>] [--report <file>] [--baseline <n>]

Exit status is non-zero when divergences exceed --baseline (default 0), so the ratchet is the
gate. Run from the repository root.
"""

import argparse
import glob
import json
import os
import sys

EXPECTED_DIR = "fixtures/expected"
MAX_DIVERGENCES_PER_FIXTURE = 20


def kind_tree(node):
    """Reduce a projected node to (kind, children), dropping tokens and spans."""
    if "kind" not in node:
        return None  # token leaf
    children = [kind_tree(c) for c in node.get("children", [])]
    return (node["kind"], [c for c in children if c is not None])


def load_units(path):
    """Return {source: kind_tree} for one projected-tree document."""
    doc = json.load(open(path))
    return {u["source"]: kind_tree(u["tree"]) for u in doc.get("units", [])}


def apply_elision(children, elide, stats, counter="elided"):
    """Remove transparent nodes from a child list.

    Used on both sides: ``elide`` names canonical wrappers the consumer does not produce,
    ``ignored`` names the consumer's own wrappers that have no counterpart in the reference.
    Transparency has to be symmetric -- handling only one side leaves the other's wrapper facing a
    real node, which reports as a disagreement when it is a representation difference.

    A transparent node is dropped when empty and replaced by its child when it has exactly one. A
    node with two or more children is *kept*: splicing its children into the parent would discard
    real structure and let a genuine disagreement pass as a mapping decision.
    """
    out = []
    for child in children:
        kind, kids = child
        while kind in elide and len(kids) <= 1:
            stats[counter] += 1
            if not kids:
                child = None
                break
            child = kids[0]
            kind, kids = child
        if child is not None:
            out.append(child)
    return out


def compare(expected, actual, mapping, ignored, path, out, stats, elide=frozenset()):
    """Walk both trees in lockstep, appending divergences to ``out``."""
    if len(out) >= MAX_DIVERGENCES_PER_FIXTURE:
        return

    exp_kind, exp_children = expected
    act_kind_raw, act_children = actual

    if mapping is None:
        act_kind = act_kind_raw
    elif act_kind_raw in mapping:
        stats["mapped"] += 1
        act_kind = mapping[act_kind_raw]
    else:
        stats["unmapped"] += 1
        stats["unmappedNames"].add(act_kind_raw)
        return  # not a disagreement: we simply have no opinion yet

    exp_children = apply_elision(exp_children, elide, stats)
    act_children = apply_elision(act_children, ignored, stats, counter="ignored")

    stats["compared"] += 1
    if exp_kind != act_kind:
        out.append(
            {"path": path, "expected": exp_kind, "actual": act_kind, "reason": "kind"}
        )
        return  # subtree shape is meaningless once the kinds disagree

    if len(exp_children) != len(act_children):
        out.append(
            {
                "path": path,
                "expected": f"{len(exp_children)} children",
                "actual": f"{len(act_children)} children",
                "reason": "arity",
            }
        )

    for i, (e, a) in enumerate(zip(exp_children, act_children)):
        compare(e, a, mapping, ignored, f"{path}.{exp_kind}[{i}]", out, stats, elide)


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--actual", required=True, help="directory of consumer projected trees")
    ap.add_argument("--map", help="ast/projection/<consumer>.json")
    ap.add_argument("--report", help="write a JSON report here")
    ap.add_argument("--baseline", type=int, default=0, help="divergences tolerated (ratchet)")
    args = ap.parse_args()

    mapping, ignored, consumer = None, set(), os.path.basename(args.actual.rstrip("/"))
    elide = frozenset()
    if args.map:
        m = json.load(open(args.map))
        mapping = m["mappings"]
        ignored = set(m.get("ignored", []))
        elide = frozenset(m.get("elide", []))
        consumer = m["consumer"]
        inventory = {k["name"] for k in json.load(open("ast/treekind.json"))["kinds"]}
        bad = sorted(set(mapping.values()) - inventory)
        if bad:
            print(f"FATAL: projection map targets kinds absent from the inventory: {bad}",
                  file=sys.stderr)
            return 1

    expected_files = sorted(glob.glob(f"{EXPECTED_DIR}/*.json"))
    if not expected_files:
        print(f"FATAL: no expectations in {EXPECTED_DIR}/", file=sys.stderr)
        return 1

    stats = {"compared": 0, "mapped": 0, "unmapped": 0, "ignored": 0, "elided": 0,
             "unmappedNames": set()}
    divergences, agreeing, missing = [], 0, []

    for ef in expected_files:
        name = os.path.basename(ef)
        af = os.path.join(args.actual, name)
        if not os.path.exists(af):
            missing.append(name)
            continue

        exp_units, act_units = load_units(ef), load_units(af)
        found = []
        for source, exp_tree in exp_units.items():
            act_tree = act_units.get(source) or next(iter(act_units.values()), None)
            if act_tree is None:
                found.append({"path": source, "expected": "tree", "actual": "nothing",
                              "reason": "missing-unit"})
                continue
            compare(exp_tree, act_tree, mapping, ignored, source, found, stats, elide)
        if found:
            divergences.extend({"fixture": name, **d} for d in found)
        else:
            agreeing += 1

    report = {
        "schemaVersion": 1,
        "generatedBy": "tools/project/conformance.py",
        "consumer": consumer,
        "fixturesExpected": len(expected_files),
        "fixturesCompared": len(expected_files) - len(missing),
        "fixturesMissing": sorted(missing),
        "fixturesAgreeing": agreeing,
        "nodesCompared": stats["compared"],
        "nodesMapped": stats["mapped"],
        "nodesIgnored": stats["ignored"],
        "nodesElided": stats["elided"],
        "nodesUnmapped": stats["unmapped"],
        "unmappedNames": sorted(stats["unmappedNames"]),
        "divergenceCount": len(divergences),
        "divergences": divergences[:200],
    }
    if args.report:
        os.makedirs(os.path.dirname(args.report) or ".", exist_ok=True)
        with open(args.report, "w") as fh:
            json.dump(report, fh, indent=2)
            fh.write("\n")

    print(
        f"{consumer}: {agreeing}/{report['fixturesCompared']} fixtures agree, "
        f"{len(divergences)} divergences, {stats['compared']} nodes compared"
        + (f", {stats['unmapped']} unmapped" if stats["unmapped"] else "")
    )
    if missing:
        print(f"  {len(missing)} fixture(s) had no consumer output", file=sys.stderr)

    if len(divergences) > args.baseline:
        print(
            f"FATAL: {len(divergences)} divergences exceeds baseline {args.baseline}",
            file=sys.stderr,
        )
        for d in divergences[:10]:
            print(f"  {d['fixture']} {d['path']}: expected {d['expected']!r}, "
                  f"got {d['actual']!r} ({d['reason']})", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
