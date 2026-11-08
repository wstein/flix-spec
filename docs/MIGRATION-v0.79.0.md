# Migrating from Flix v0.77.0 to v0.79.0

The pin moves from `4a5b60a31ac03bb762f68b554a0fc2b6f4d982b9` to
`36d923ca325dc466b28c63419667e6026a80f920`, including the intervening v0.78.0 changes.
The official v0.79.0 release asset is 39,329,362 bytes and has SHA-256
`8fad2aa09567a7edabcc43f4101fa6199131df2c22929e001c87659d764f6dcf`.
It remains digest-only: no build attestation or embedded commit stamp is established.
The Maven data revision resets to `0.79.0`; this change does not publish it.

## v0.78.0: predicate-type tails

`Parser2` replaces `nameUnqualified(NAME_VARIABLE)` with `ttype()` after the optional `|`
in `Type.Extensible`, `Type.Schema` and `Type.SchemaRow`. A variable remains accepted, but
now appears under `Type.Type`/`Type.Variable` rather than directly as `Ident`. Compound tails
such as `List[r]` are accepted too. This is a projection change even for existing valid input,
not merely an extension of accepted source. Six positive fixtures distinguish each form with a
variable and a compound tail; three negative fixtures preserve missing-tail recovery.

Neither TreeKind nor TokenKind names change at v0.78.0. The separate Weeder2 phase stops
accepting `pub` on struct fields; this repository's positive category asserts parsing only.

## v0.79.0: comments and documentation ownership

The grammar now sees only non-comment tokens (`State.visible`). Comments can occur between
lookahead tokens without changing the grammar decision. The lexer is unchanged across all three
releases: spacing before `.` still raises `FreeDot`, and tight token rules still apply.

The tree builder reinserts comments after parsing. Same-line trailing comments become
`PlainComment` children of the innermost node open at the preceding visible token. Other leading
comments precede the next node; the run from its first doc token onward becomes a `DocComment`,
the first child of the outermost documentable node starting at the next visible token.
Doc comments in trailing runs, expressions or at EOF without an owner produce
`MisplacedDocComments` and remain present under `PlainComment`. A leading doc can own a declaration
with annotations and modifiers, and a struct field has its own documentation owner.

TreeKind remains 192 names, with two additions (`PlainComment`, `DocComment`) and two removals
(`CommentList`, `Doc`), recorded in `ast/retired.json`. No retained kind is re-parented. TokenKind
remains 159 names with unchanged digest; Annotation remains 16 names with unchanged digest.
Consumers must replace the old comment mappings and reproduce documentation ownership. Both new
comment kinds retain their identity in canonical trees.

## Curated evidence and derived data

All 53 previous citations in `ast/unattachable.json`, `ast/transparency.json` and
`defects/ledger.json` were checked against the new source in context. The obsolete `Doc` elision
rule was removed; the remaining 20 rules still satisfy their arity conditions. Statements about
single-child grammar wrappers permit extra reinserted comment children; elision remains conditional
on the actual arity. Normalization removes 2,246 of 4,619 nodes (48.6%) across 163 fixtures.

The six unattachable kinds remain unattachable. Both recorded defects still reproduce:
`Predicate.ParamUntyped` is still unconditionally overwritten, and Weeder2 still omits `NameMath`
from its infix operator match. Historical upstream issue-search statements retain their original
v0.77.0 scope; this migration does not claim a new search or change their filing status.

The corpus changes from 893 files (713 main, 180 examples) to 894 (717 main, 177 examples).
893 parse without error, and all 893 clean parses are lossless. The remaining file is the
upstream resiliency input `main/test/flix/resiliency/ford-fulkerson-prefix.flix`, an intentionally
truncated declaration (`def maximumFlow(g: Graph) =`). It reports `UnexpectedToken` at lines 10
and 11. Its source is unchanged from v0.77.0, whose corpus already had one non-clean parse;
this is existing malformed test input, not a claim that the full corpus parses cleanly. Status has 182 reachable-covered
and four fixture-only TreeKinds, six structurally unattachable TreeKinds, and no unknown or
corpus-only kinds. Token status has 152 reachable-covered, six fixture-only and one unattachable
sentinel, with no unknown or corpus-only entries.

## Consumer migration

Update the exact pin and artifact digest, update comment mappings, and accept full type trees in
all three predicate-tail positions. Re-measure fixture revisions and baselines after updating the
fixtures. Diagnostic identities include kind and line; comment ownership changes can change those
identities even when the comment token text is unchanged. The existing
`declarations__doc-comment-misplaced-before-paren` fixture changes from
`MisplacedDocComments` at line 1 plus two `UnexpectedToken` errors at line 3 to just
`MisplacedDocComments` at line 2. Compare the committed raw and canonical
forms separately when evaluating recovery and normalization.
