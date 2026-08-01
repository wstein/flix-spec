# Phase 0 spike: findings and decision

Answers the four questions in implementation plan section 7 ("Phase 0"), in order, against
`flix/flix` pinned at `v0.75.1` (`318bb51a…`, tree `294b9ac53…`). Prototype and verification script
live at `tools/project/` and `tools/oracle/`; run `tools/oracle/fetch.sh` then
`tools/project/verify.sh` to reproduce every claim below.

## Q1 — Does the coupling boundary of section 4.1 hold?

**Yes.** A standalone Gradle + Scala module (`tools/project/`) compiles against `flix.jar` as a
plain external jar dependency — no recompilation of Flix's 448 Scala sources, no matching of its
`scalacOptions` — and correctly drives `Reader.run → Lexer.run → Parser2.run` to yield a
`SyntaxTree.Root` for a real `.flix` file. `application`/`scala` are Gradle's stock plugins; the
only wiring needed was `implementation("org.scala-lang:scala-library:2.13.18")` alongside
`implementation(files(".oracle/flix.jar"))`. Nothing about the jar's construction (mill vs.
anything else) leaked into the consuming build.

Two internal-but-public APIs had to be worked around, both cheap and stable across releases
(neither is a compiler-internals reach-around; both are `Flix`'s own public surface):

- `Flix.threadPool` is a public `var`; it must be set (`new ForkJoinPool(n)`) before calling any
  phase, or `Lexer.run`'s parallel map throws `NullPointerException`. `Flix.check()` normally does
  this via a private `initForkJoinPool()`; driving phases directly means doing it yourself.
- `Reader.run` takes `List[Input]` and `AvailableClasses`, both public. `Input.RealFile(path,
  SecurityContext.Plain)` and `AvailableClasses.empty` are enough for a single real file; no need
  for `Flix`'s private `getInputs`.

Route B (reflection over the built compiler, section 4.2) was validated the same way: `ListKinds`
walks `TreeKind`'s sealed hierarchy with `scala.reflect.runtime.universe`, entirely against the
jar's classes, and returns **exactly 192** leaves (191 `case object` + `ErrorTree`) — matching the
plan's hand-count of `SyntaxTree.scala` with no source parsing at all.

## Q2 — Does the v0.75.1 release ship a `flix.jar` asset?

**Yes**, at `https://github.com/flix/flix/releases/download/v0.75.1/flix.jar`. And a concrete,
previously-unverified finding: **it is not byte-identical to a jar built here from the same
commit** via `./mill flix.assembly` on a clean checkout, same mill (1.1.5), same JDK (21):

| | entries | SHA-256 |
| --- | --- | --- |
| release asset | 16776 | `e3177700aead8a22a42c910e73bfb8a326fefdbab4e3eaeaf6d55c328a6bd938` |
| built here | 16794 | `bfca1bac75142a67fafca163f2c97b71075f2cdb93bdd680259c75fafacfdb4b` |

18 extra files in the self-built jar — almost certainly transitive-dependency resolution drift
over the ~3 weeks since the release was cut (coursier resolving today's patch releases of
non-pinned transitive deps), not a real difference in Flix's own code. But it means "rebuild it
ourselves" is **not** a reproducibility guarantee against what users actually run, and the two
candidate oracles are demonstrably different artifacts.

**Decision: pin the release asset's digest** (`pin.json.oracleArtifact`), not a self-built one.
Reasons: it is what users actually run, so it is the more faithful "operational oracle"; it removes
an entire axis of drift (transitive-dependency resolution changing between when the release was
cut and when flix-spec happens to build); and rebuilding didn't even reproduce it, which undercuts
the appeal of building from source in the first place. `tools/oracle/build-from-source.sh` remains
as the documented fallback for a future pin whose release has no jar asset — it explicitly does not
auto-update `pin.json`, because blessing a self-built digest as the oracle is a human call, not a
default.

## Q3 — Does `SyntaxTreePrinter.scala` do most of the projection work?

**Yes, as a traversal shape to copy — not as reachable code** (section 4.1 fact 3: it's reachable
only via `AstPrinter.printDocProgram`, gated on a debug flag, and emits pretty-printed `DocAst`
**text** for a whole run, not JSON per fixture — recovering structure from that would mean parsing
pretty-printed text, exactly what section 4 bans). `tools/project/src/main/scala/spike/Extract.scala`
reimplements its walk directly against `SyntaxTree.Tree`/`Child`: match on `TreeKind.ErrorTree`
specially (not a case object, so no free `toString`), recurse into `Token | Tree` children.
`SyntaxTree.Tree` additionally carries `loc: SourceLocation`, which the printer discards but the
projection schema (section 3.1) needs for `span`; that's the one addition beyond copying the
printer's shape.

Confirmed against a real fixture: a syntactically valid file yields a fully nested tree with tokens
and spans at every leaf; a deliberately broken one (`fixtures/negative/unclosed-paren.flix`)
recovers into a well-formed tree containing an `ErrorTree` node at the expected position, with the
parser's own diagnostic (`UnexpectedToken(..., unclosed-paren.flix:3:1)`) available alongside —
directly exercising the class+line semantics section 3.4 recommends for negative fixtures.

## Q4 — Is Route A's Scala 3 grammar robust enough to extract `TreeKind`?

**Not evaluated further — moot.** Per the plan, this question is conditional on Route A surviving
Q1, and Q1 didn't need Route A: Route B works cleanly against the jar with no source parsing, no
vendored grammar, and no Scala-3-grammar-on-Scala-2.13 hazard. Confirmed only that grammars-v4 still
carries `scala/scala3/Scala3Lexer.g4` and `Scala3Parser.g4` (so Route A remains *possible* if ever
needed), and went no further; building a throwaway ANTLR pipeline to answer a question the winning
route doesn't need would be effort spent on the bridge the plan already says to dismantle.

## A finding beyond the four questions: bare `TreeKind` names are not unique

`ListKinds`' full output (`tools/project` — `./gradlew :tools:project:listKinds`) surfaces something
none of revisions 1–3 recorded: of the 191 non-`ErrorTree` leaves, **13 simple names are reused
across different sub-traits** — `Apply`, `Argument`, `ArgumentList`, `Ascribe`, `Binary`, `Effect`,
`ExtTag`, `Literal`, `Record`, `RecordFieldFragment`, `Tuple` (×3), `Unary` (×3), `Use`, `Variable`
— 28 leaf positions collapse to 13 bare strings. `SyntaxTree.TreeKind` has no `toString` override,
so `Expr.Apply` and `Type.Apply` both print as plain `"Apply"` — which is exactly what
`SyntaxTreePrinter` (and this spike's own `Extract`) emit today.

This matters directly for section 3.1 and 3.3: the projection schema's `kind` field and
`ast/treekind.json`'s name-set digest cannot be built from bare case-object names — at least 13 of
them are ambiguous. Phase 1 needs a qualified identifier (e.g. `Expr.Apply` vs. `Type.Apply`, using
the enclosing sub-trait, which reflection already gives for free via `knownDirectSubclasses`'
owner chain) before `ast/treekind.json` or the projected-tree `kind` string can claim to identify a
node kind uniquely.

## Recommendation

- **Coupling boundary:** flix-spec never compiles Flix. `tools/oracle/fetch.sh` downloads and
  verifies the pinned release jar; `tools/project/` (Gradle + Scala) builds against it.
- **Extraction route:** B (reflection), for both the `TreeKind` inventory and the projected-tree
  walk. No ANTLR, no vendored grammar, no `Scala3LexerBase.java`.
- **Oracle artifact:** the upstream release's `flix.jar`, pinned by SHA-256 in `pin.json`, not a
  jar built here. `build-from-source.sh` is the documented fallback for a pin with no release
  asset, and does not auto-promote its output to canonical.
- **Carry into Phase 1:** qualified (sub-trait-prefixed) `TreeKind` names in `ast/treekind.json` and
  the projection schema — the bare-name collision above is a real gap, not a hypothetical one.
