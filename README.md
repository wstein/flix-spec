# flix-spec

Shared test infrastructure for parsers of [Flix](https://github.com/flix/flix): a machine-readable
inventory of the language's syntax tree kinds, a corpus definition pinned to an upstream release,
and fixtures with expected tree shapes — all derived from the reference compiler at a pinned
release, and used by independent parser implementations to check that they agree with it.

**What this is not:** a specification of Flix, and not a conformance suite in the Test262 sense.
Test262 derives from ECMA-262, a normative document independent of every implementation; this
derives from the reference implementation itself. It has no independent authority over the
language and does not claim any. See `tmp/implementation_plan.md` (not checked in) for the full
design rationale.

## Status

**Phase 0 (feasibility spike) complete.** See [`docs/phase0-spike.md`](docs/phase0-spike.md) for
the findings and the resulting decisions:

- flix-spec never compiles Flix. It consumes a pinned, checksummed `flix.jar` (`pin.json`).
- The extraction route is reflection over the built compiler's `TreeKind` hierarchy plus a direct
  walk of `SyntaxTree.Tree`, not a source-level grammar.
- The build tool is Gradle, not mill — mill is invoked exactly once, as a throwaway fallback for a
  pin whose release has no jar asset (`tools/oracle/build-from-source.sh`).

Phase 1 (pin, contracts, and the committed `ast/treekind.json` inventory) has not started.

## Layout

```text
pin.json                 # oracle pin: upstream tag/commit/tree hash, artifact digest
docs/phase0-spike.md      # Phase 0 findings and decision record
tools/oracle/             # fetch.sh (pinned jar + checksum) and build-from-source.sh (fallback)
tools/project/            # Gradle + Scala module: emits a projected tree, enumerates TreeKind
fixtures/                 # spike fixtures used by tools/project/verify.sh
```

## Running the spike

```sh
./tools/oracle/fetch.sh                                          # fetch + verify the pinned jar
./gradlew :tools:project:extract --args=path/to/file.flix         # emit one projected tree
./gradlew :tools:project:listKinds                                 # enumerate all TreeKind leaves
./tools/project/verify.sh                                          # determinism + count checks
```

## License

Apache-2.0, matching `flix/flix`. See [`LICENSE.md`](LICENSE.md).
