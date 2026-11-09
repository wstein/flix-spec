# Syntax production coverage

`ast/coverage.json` measures whether fixtures emit each tree or token kind. It cannot distinguish
productions that share a kind. `ast/syntax-variants.json` adds a curated catalogue of explicit
production variants with executable evidence. It is a bounded catalogue, not an enumeration of the
Flix grammar, and its percentage must never be described as grammar completeness.

Run `./gradlew -q :tools:project:checkSyntaxCoverage` from the repository root. The checker reads
committed projections and source fixture existence; it does not invoke the oracle or generate new
trees. The normal fixture regeneration and validation gates establish that those projections came
from the pinned compiler. A passing syntax check alone cannot establish that provenance.

Each variant names its production, distinguishing syntax, and a line range in the upstream parser
or lexer at the catalogue's exact `upstreamCommit` and `upstreamTag`. Both identity fields must
match `pin.json`. Re-read these citations and predicates on every pin bump; changing the identity
without reading the upstream production is not verification.

Each evidence entry names a source fixture, projection form (`raw` or `normalized`), an exact
multiset of expected diagnostic `(kind, line)` pairs, required patterns, and forbidden patterns.
The checker requires the fixture and projection to exist, the projection form and upstream commit/oracle digest to agree with the pin, and exactly
one unit to identify that source. It validates the catalogue schema, rejects duplicate variant IDs,
and checks all predicate kind/token names against the pinned inventories. The checker emits JSON with a `covered` or `missing` status and errors for every declared variant.
Missing evidence exits nonzero and names the variant and unmet predicate; a kind occurring elsewhere cannot repair it.

A pattern selects a node by `kind` or a leaf by `token`, with optional exact token `text`:

- `children` matches an ordered subsequence of **direct** children, without reusing a child.
- `exactChildren: true` requires the complete direct child sequence, including its length.
- `firstChild` constrains the first direct child.
- `descendants` requires each pattern somewhere strictly below the selected anchor.

All constraints in a pattern must hold on the same anchor. Nest patterns to express a path; a
constraint below a declaration cannot be satisfied by a sibling declaration. A pattern containing
only text, an empty predicate, an unknown property or an unknown vocabulary name is invalid.
Token text uses exact equality, never a source substring search. Diagnostics compare kind and line;
message and column remain advisory, matching the conformance contract.

The initial catalogue covers six row-tail combinations (extensible type, schema, schema row, each
with variable and complex tail), missing-tail recovery for all three forms, and selected comment
positions and documentation attachment. Documentation evidence binds the comment to its named
declaration or field, and negative documentation fixtures require the misplaced-documentation
diagnostic. Further syntax is outside the catalogue until somebody declares variants and supplies
observable evidence. Additions should distinguish a production decision, rather than repeat a
kind census under another name.

Tests exercise structural, token, ordering, first-child, diagnostic, provenance and missing-file
failures. When changing a predicate or adding a variant, also mutate its evidence so the gate fails
for the intended reason. Run the source checks with `umask 022`; no standalone oracle build is
needed for this checker.

## Consumer-driven extension matrix

The catalogue now declares 32 variants, including eleven additions in the matrix below.
The number describes this finite set of decisions, not a percentage of Flix grammar.
The existing nine row-tail variants already cover the consumer's variable-only row-tail
limitation; duplicating those witnesses would add no new production decision.

| Decision and variant IDs | Consumer evidence and reason to select it | Executable witness |
| --- | --- | --- |
| Expression precedence: `expression.precedence.multiply-in-add`, `expression.associativity.cons-right`, `expression.associativity.subtract-left` | JetBrains' precedence-chain mapping requires a wrapper to become a binary node when its operator is present ([CONFORMANCE](CONFORMANCE.md#precedence-chains)). The mixed precedence chain and equal-precedence left/right pairs control that boundary; they are not newly measured consumer failures. | Exact normalized binary children include named operands and `Operator`; multiplication nests on the right of addition, cons on the right of cons, subtraction on the left of subtraction. |
| Type arrows: `type.arrow.right-associative`, `type.arrow.result-effect` | The tree-sitter 0.79 remeasurement finds missing operator children in both existing arrow fixtures: expected/actual arities 3/2 and 4/3, followed by `Operator` versus `Type.Binary` / `Type.Variable`. These are current measured differences, rather than extrapolation from the old pilot. | Exact raw binary children bind the right-nested arrow and the result/effect sequence, including the backslash token. |
| Interpolation: `interpolation.nested`, `interpolation.adjacent` | JetBrains retains an exact EOF interpolation recovery difference ([migration review](https://github.com/wstein/flix-jetbrains-plugin/blob/775e3a814f91f3ad7f1ae00a7afebe53eb230454/docs/conformance-migration.md)). Nested and adjacent complete interpolations are nearby positive controls for Parser2's opener distinction, not claims of additional consumer failures. | Exact raw outer/inner interpolation children distinguish a nested opener from the single `}${` boundary between sibling expressions. |
| Declaration prefixes: `declaration.prefix.single`, `declaration.prefix.multiple` | Tree-sitter's [recorded baseline](https://github.com/wstein/tree-sitter-flix/blob/349e4d3d23c71bd13e4e414b708714e5ca3c78b2/conformance/baseline.json) records a real fix for annotation/modifier list arity. Single and multiple prefixes preserve the production distinction that required that fix. | Ordered lists belong to the named declaration; exact list children distinguish one from two annotations/modifiers. Nonempty singleton lists also survive normalization. |
| Recovery: `recovery.operator-gap`, `recovery.interpolation-eof` | [CONFORMANCE](CONFORMANCE.md) records the attempted synthetic `OperatorError` consumer implementation; JetBrains' migration review retains the separate synthetic EOF error identity. No adapter should synthesize reference-shaped recovery to hide these differences. | Raw `OperatorError` remains inside `Operator` inside binary operands. EOF evidence requires both the inner interpolation error and a separate direct root error containing `Err`, plus exactly `UnterminatedStringInterpolation` at line 1. The missing operator has no parse diagnostic; the weeder reports it later. |

The tree-sitter remeasurement used Flix v0.79.0 / commit
`36d923ca325dc466b28c63419667e6026a80f920`, oracle digest from `pin.json`, and
fixture revision `aa16763bda3466465bc0735b4c2b14dcf2e072fc64893b49991f74cefeeb312f`
(the 163-fixture input before these three new positive fixtures). It is separate
from the committed older-pin consumer baseline. Its consumer head is
`b6c4b3590c565108bda44023bb938a968114e2a5`, projection-map SHA-256
`e43fb516c61ed8c2acaaaa25b603d29ca2be78eb648ee95d60d7c640f3093b11`,
and runner SHA-256 `ea02ead1dbe3c5d9d22f6bfffec7f8207715f04ebc0d33a694e9f75aebc1fec2`.
Reproduce from that consumer checkout with
`node scripts/flix-spec-conformance.mjs /path/to/163-fixture-flix-spec --runner /path/to/runner.jar --remeasure --out /path/to/report`.
Adding witnesses here does not
approve new consumer baselines or declare their gates passing.

Parser2 citations are in each variant. Prefix ordering is additionally established
by `Decl.declaration` at lines 1016–1022; nonempty lists come from lines 1430–1445.
For EOF, `Decl.declaration` at lines 1016–1047 retains the top-level error,
`closeWithError` at lines 458–464 avoids duplicating lexer diagnostics, and
`interpolatedStringExpr` at lines 3275–3314 retains the inner error. The lexer
emits the unterminated-interpolation diagnostic at `Lexer.scala:646`.

`ProductionCatalogueTest` checks all eleven new witnesses, rotates each matched
anchor's direct children while retaining its kinds/tokens, and runs a token-text
mutation through the evidence gate for every addition. EOF diagnostic kind and
line mutations must fail specifically with a diagnostic mismatch. These checks
preserve projection provenance so a hash failure cannot substitute for testing
the production predicate. The existing defect ledger and incomplete corpus case
remain part of their existing gates.
