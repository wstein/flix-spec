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
| Id | Defect | Component | Disposition | Upstream | Triaged at pin |
| --- | --- | --- | --- | --- | --- |
| `FLIX-0001` | Predicate.ParamUntyped is dead by assignment in Parser2.param() | `Parser2` | accepted-upstream-defect | not filed | `4a5b60a31ac0` |
| `FLIX-0002` | Weeder2 crashes on a math-name operator used infix | `Weeder2` | accepted-upstream-defect | not filed | `4a5b60a31ac0` |

2 entries, each re-checked against the pinned oracle on every run. See [`defects/ledger.json`](../defects/ledger.json) for each reproducer, its citations and the full impact note.
<!-- /generated: defects -->

## What makes an entry

Every entry carries an owner, a disposition, a minimized reproducer, source citations, an explicit
upstream-report status, and the pin it was last triaged against. `defects/ledger.json` is the data;
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

## Entries expire when the oracle moves

Each entry records the upstream commit it was last triaged against, in `reviewedAtPin`. Once
`pin.json` names a different one, the build fails until a human re-reads the entry and either
restamps it or closes it. A ledger nobody revisits is indistinguishable from having no ledger, and a
pin bump is exactly the moment a defect may have been fixed.

**This was a wall-clock `review` date until 0.77.2, and that was wrong.** It put a fuse in every
tag: re-running CI on an old commit after its entries expired would fail, although nothing about
that commit had changed and the artifacts it published were still exactly what it published. At the
time of the change both entries were 34 days from doing precisely that to `v0.77.0` and `v0.77.1`.

The reasoning that replaced it is simpler than the cost it removed. Time passing is not evidence
about a defect. The oracle changing is — it is the only thing that can make one of these entries
stop being true — and `docs/PIN-BUMP.md` already requires every curated file to be re-read at a
bump, so the ledger now expires on the same trigger as `ast/transparency.json` and
`ast/unattachable.json` rather than on a clock of its own.

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
