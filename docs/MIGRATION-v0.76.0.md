# Flix v0.76.0 migration and repository review

The oracle advances from Flix v0.75.2 (`40949531b4d42e5eaf2e4b9997537eaf793c24e7`)
to v0.76.0 (`f2d4678c20bff1242f4cad5e23144db91027b762`). The package version is
`0.76.0`. This migration was reviewed against the local upstream Git tag, with independent
reviews of upstream parser changes, Scala validation, and build/publication infrastructure.

The official [release artifact](https://github.com/flix/flix/releases/tag/v0.76.0) has SHA-256
`d8d9a3870e199c03ed6364ea9430f56f67bfd38c332c411628a6a7cb88b2b0b4`, matching the
release API's digest. It contains 20,138 entries and is 39,149,212 bytes. The artifact remains
recorded as digest-only; this migration does not establish build provenance linking its bytes
to the source commit.

## Compatibility

- The 191 TreeKinds and 158 TokenKinds, including their qualified names and vocabulary digests,
  are unchanged. No kinds were added, removed, or re-parented.
- Effects now accept type parameters. Generic operations remain invalid and now report
  `IllegalOperationTypeParams` rather than `IllegalEffectTypeParams`.
- Malformed `match` and `ematch` expressions now retain the match node and scrutinee through
  ordinary recovery. Six new fixtures cover generic effects, generic operations, and missing
  match bodies at EOF and before another declaration.
- Among the previous 138 fixtures, trees and diagnostic kind/line pairs are unchanged. Only
  `expressions__match-rule-wrong-arrow` changes diagnostic message context from `OtherExpr`
  to `MatchBody`, in both raw and normalized output. All provenance headers were regenerated.
- Transparency rules and kind statuses are unchanged. Citations in both curated evidence files
  and the defect ledger were checked at the new commit, and inaccurate explanations were corrected.
  Normalization removes 753 of 4,398 nodes across the expanded suite.
- The parser adapter now uses Flix's `ThreadPool` API and shuts down each pool in `finally`.

Consumers must update their exact pin checks and accept the new parser behavior. No artifact
schema version changes are needed. Stricter validation now rejects malformed documents that
previously slipped through the implementation despite violating the published schema.

## Review findings fixed

| Area | Finding and correction |
| --- | --- |
| Schema validation | Nested `oneOf`, referenced constraints, inline objects, scalar array items, and integer precision were incompletely checked. Recursive checks and regression tests now enforce them. |
| Source invariants | Malformed nested nodes could pass shape checks or crash token accounting. Validate shape before accounting and report a failed check. |
| Oracle identity | Fixture and reachability generators computed the jar digest without comparing it to the pin. Both now reject a mismatched jar. |
| Corpus parsing | Lexer diagnostics were omitted when counting clean parses. Both lexer and parser diagnostics now contribute. |
| Corpus fetching | Cached clones could not advance to missing commits, and modified or untracked source files could contaminate measurements. Fetch missing commits and reject modified/untracked sources. Count only files: v0.76.0 adds a directory named `dev.flix`. |
| Oracle fetching | Failed downloads could destroy the installed jar. Verify a temporary download before replacing it; reuse a verified cache offline. |
| Publication checks | Pages checked only the tree inventory; release checks missed untracked generated output. Both now check the full regenerated artifact set, including new files. |
| Build and packaging | Broad formatter targets caused Gradle dependency errors in combined commands; standard `assemble` produced no package. Scope formatting to Scala sources and connect `assemble` to the artifact jar. The publication probe now uses the repository's Gradle wrapper. |

## Validation

Verified with JDK 21 and the repository's Gradle wrapper:

- All 123 unit tests pass, including the added malformed-schema and source-invariant regressions.
- `tools/project/verify.sh` passes: deterministic extraction, all 288 projected documents,
  normalization, losslessness, conformance, recovery, mutation detection, vocabulary schemas,
  and the defect ledger.
- Corpus identity/count verification passes for 900 files (712 under `main`, 188 under `examples`).
  Reachability processes all 900; all 897 clean parses reconstruct their source.
- Coverage remains 185/191 TreeKinds and 157/158 TokenKinds. Every corpus-reachable kind has a
  fixture; there are no unknown or corpus-only statuses.
- `spotlessCheck test :packaging:assemble` and `spotlessApply test` pass.
- ShellCheck, Bash syntax checks, and Actionlint pass for the reviewed scripts/workflows.
  Isolated download checks confirm cache reuse and preservation of the installed jar on failure.

The existing upstream defect `FLIX-0001` still reproduces: `Predicate.ParamUntyped` is overwritten
before emission. Its ledger entry remains open and not filed upstream. No package was published
and no upstream source was modified during this migration.
