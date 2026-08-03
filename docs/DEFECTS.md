# Reference-compiler defect ledger

Defects in the Flix reference compiler that this suite has observed, cannot falsify, and therefore
inherits.

## Why a ledger exists at all

The README states the accepted limitation up front: a derived suite cannot falsify its reference. If
Flix has a bug, `flix-spec` inherits it and reports every agreeing parser as correct. That is a
deliberate trade — it buys an oracle that cannot drift.

What the trade does not license is silence. A shared defect nobody wrote down stops being a defect
and quietly becomes the specification. The consumer who pays is the one who implemented the
reference's *intent* rather than its *behaviour*, and whose divergence report then blames their
parser for being right.

So "zero drift" remains the goal for compatibility, and it is not a claim of correctness. Where the
two come apart, the ledger is where that is recorded rather than normalised away.

<!-- generated: defects -->
| Id | Defect | Component | Disposition | Upstream | Review by |
| --- | --- | --- | --- | --- | --- |
| `FLIX-0001` | Predicate.ParamUntyped is dead by assignment in Parser2.param() | `Parser2` | accepted-upstream-defect | not filed | 2026-11-01 |

1 entry, each re-checked against the pinned oracle on every run. See [`defects/ledger.json`](../defects/ledger.json) for each reproducer, its citations and the full impact note.
<!-- /generated: defects -->

## What makes an entry

Every entry carries an owner, a disposition, a minimized reproducer, source citations, an explicit
upstream-report status, and a review date. `defects/ledger.json` is the data;
`schemas/defect-ledger.schema.json` is the contract; `./gradlew :tools:project:validateDefects`
enforces both, and runs inside `verify.sh`.

There is deliberately no `unclassified` disposition. An observation without a decision is not ready
to be an entry, and a ledger that accepts them becomes a parking lot.

## Entries are falsifiable, not prose

This is the part that distinguishes the ledger from a list of grievances. Each entry declares how the
defect shows in a projected tree:

```json
"assert": {
  "parsesCleanly": true,
  "absentKinds": ["Predicate.ParamUntyped"],
  "presentKinds": ["Predicate.Param", "Predicate.ParamList"]
}
```

`validateDefects` re-parses the reproducer with the pinned oracle on every run and checks it. So when
upstream fixes the defect, the assertion stops holding and **the build fails**, naming the entry and
saying it appears fixed. Closing it is then a deliberate act. Without that, a ledger degrades into
folklore about bugs that were repaired years ago.

It cuts the other way too: if a reproducer stops demonstrating what it claims — because the syntax it
used was reworked upstream — the build says the reproducer needs minimizing again, rather than
letting a vacuous entry sit there looking like evidence.

## Entries expire

Past its `review` date an entry fails the build until a human re-triages it. A ledger without expiry
accumulates entries nobody has read in a year, which is indistinguishable from having no ledger.

**The cost of that gate, stated plainly:** it is time-based, so re-running CI on an old commit or tag
after one of its entries has expired will fail even though nothing about that commit changed. That is
the intended direction of the ratchet — staleness should be loud — but it means a historical rebuild
may first need the ledger's `review` dates advanced. This is the one place in the repository where a
check depends on the wall clock rather than on the pinned inputs, and it is a deliberate exception
rather than an oversight.

## Relationship to `ast/unattachable.json`

The two files are close cousins and easy to confuse.

| | `ast/unattachable.json` | `defects/ledger.json` |
| --- | --- | --- |
| Claims | this kind can never appear in a tree | the reference does something wrong |
| Feeds | `ast/status.json`'s per-kind status | this document |
| Refuted by | a fixture or corpus file containing the kind | its own reproducer ceasing to demonstrate it |

A kind can appear in both, and `Predicate.ParamUntyped` does: it is unattachable *because* of the
defect. The evidence file records the consequence for coverage accounting; the ledger records the
cause, and is what a consumer reads to find out that agreeing with the reference here means agreeing
with a bug.

## Reporting upstream

`upstreamStatus` is either `filed` (with a URL) or `not-filed` (with none), and the validator rejects
any other combination. "Nobody told them" is then a recorded state rather than an omission, which is
the honest position for a repository that observes defects as a side effect of building test
infrastructure and has no standing to triage them for the Flix project.
