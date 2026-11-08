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
