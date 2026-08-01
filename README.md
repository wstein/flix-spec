# flix-spec

Shared test infrastructure for parsers of [Flix](https://github.com/flix/flix): a machine-readable
inventory of the language's syntax tree kinds, a corpus definition pinned to an upstream release,
and fixtures with expected tree shapes — all derived from the reference compiler at a pinned
release, and used by independent parser implementations to check that they agree with it.

**What this is not:** a specification of Flix, and not a conformance suite in the Test262 sense.
Test262 derives from ECMA-262, a normative document independent of every implementation; this
derives from the reference implementation itself. It has no independent authority over the
language and does not claim any. See [`tmp/implementation_plan.md`](tmp/implementation_plan.md) for the full
design rationale.

## Status

**Phase 1 (Pin, contracts, AST inventory & corpus specification) complete.**

Key components established:
- **Oracle Pin Contract (`pin.json`)**: Pinned to upstream release `v0.75.1` (`318bb51a...`, tree `294b9ac53...`) with release asset SHA-256 (`e3177700...`), self-built artifact digest, attestation metadata, and load-bearing compiler execution settings.
- **Projection Contract ([`docs/PROJECTION.md`](docs/PROJECTION.md))**: Defines canonical projected tree format, load-bearing structural constraints, normalized whitespace/comments, and consumer projection maps.
- **JSON Schemas ([`schemas/`](schemas/))**: JSON Schema draft-07 definitions for `ast/treekind.json` (`schemas/treekind.schema.json`) and canonical projected trees (`schemas/projection.schema.json`).
- **Committed AST Inventory ([`ast/treekind.json`](ast/treekind.json))**: 192 reflected `TreeKind` nodes (qualified names, parent traits, case-object/case-class forms) with exact SHA-256 digest `ef4c5a85...`.
- **Corpus Definition ([`corpus/`](corpus/))**: Pinned 873 `.flix` file inventory (688 under `main/`), inclusion rules, and tree-hash verified fetch script (`corpus/fetch`).
- **CI & Verification**: Fast tier GitHub Actions (`.github/workflows/verify.yml` and `oracle.yml`), SHA-pinned actions, runner pinned to `ubuntu-24.04`, and Dependabot integration.

## Layout

```text
pin.json                 # Oracle execution contract & artifact SHA-256 digests
NOTICE.md                # Third-party provenance and attribution
docs/
  PROJECTION.md          # Projection specification & conformance contract
  PIN-BUMP.md            # Step-by-step checklist for pinning new releases
  phase0-spike.md        # Feasibility spike findings and decision record
schemas/
  treekind.schema.json   # JSON Schema for ast/treekind.json
  projection.schema.json # JSON Schema for canonical projected trees
ast/
  treekind.json          # GENERATED — 192 qualified syntax tree kinds & SHA-256 digest
corpus/
  corpus.json            # Pinned corpus inventory specification & inclusion rules
  fetch                  # Script to clone, check out, and verify corpus tree hash
fixtures/                # Test fixtures (positive / negative)
tools/
  oracle/                # fetch.sh (pinned jar + checksum) and build-from-source.sh (fallback)
  project/               # Gradle + Scala module: TreeKind extractor, tests, projection harness
```

## Running Verification & Generators

```sh
./tools/oracle/fetch.sh                                          # Fetch & verify pinned oracle flix.jar
./corpus/fetch                                                    # Fetch & verify pinned source corpus
./gradlew :tools:project:generateTreeKind                         # Extract ast/treekind.json via reflection
./gradlew :tools:project:extract --args=path/to/file.flix         # Emit a projected concrete syntax tree
./gradlew test                                                    # Run ScalaTest suite
./tools/project/verify.sh                                        # Run end-to-end Phase 1 verification suite
./gradlew spotlessApply                                           # Format Scala code via scalafmt
```

## License

Apache-2.0, matching `flix/flix`. See [`LICENSE.md`](LICENSE.md) and [`NOTICE.md`](NOTICE.md).
