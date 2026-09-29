# Pre-release consumer pilots

## Follow-up qualification — 2026-09-29

All five follow-ups are implemented and both real consumers pass against the
**same final staged runner**. This means passing their reviewed contracts, not
zero differences or complete language coverage. Initial failing measurements
are retained below rather than rewritten as though the migration were automatic.

| Follow-up | Result |
| --- | --- |
| Tree-sitter metric migration | Commit `ad132be`: floor explicitly moves 93→92. Same 2002 nodes compared and 86 differences; old denominator 2162 = 2183 − 18 elided − 3 flattened nodes. The adapter now rejects a different report schema. |
| JetBrains adapter/map | Commit `e288885`: real AST lexer leaves replace the lossy PSI child view. Debug interpolation maps to Expr.Expr; CommentList grouping is explicitly flattened with rationale. Structure returns 13→4 differences, with compared nodes increasing 1264→1271. Token accounting and positions pass for all 138 fixtures. |
| Reviewed recovery | Commit `feb2c95`: six differences across misplaced doc comments, removed-law recovery and synthetic EOF recovery are recorded by exact identity. Four pre-existing structural differences are likewise identity-gated; floors are 36%/40%. Unreviewed identities, stale revisions and lost tokens are fault-injected and fail. |
| CI adoption | Commit `af7bca7`: published-plugin `conformance/` build is required by the JetBrains CI release-draft dependency graph; duplicated Kotlin comparator removed. Source revision is pinned for pre-release local Maven staging. |
| Artifact qualification | All three staged jars cold-resolve with matching build SHA-256; plugin marker resolves. Both external distribution acceptance suites, both real consumer gates and flix-spec verification pass. No remote publication or tag was created. |

JetBrains' corrected token output found another genuine runner defect: a token
ending at line N+1, column 1 after a final newline was rejected. Commit `411bd32`
accepts that valid EOF boundary and tests LF/CRLF plus out-of-range positions.

Final real-consumer results:

- Tree-sitter: 147 fixtures, 86 structural / 54 recovery / 7 accept-reject
  differences; structural/recovery depth 92%/67%; exit 0. Grammar tests 114/114,
  adapter tests 4/4 and lint pass. Counts were not relaxed.
- JetBrains: 138 fixtures, 4 structural / 6 recovery differences bound to consumer
  and fixture revision; depth 36%/40%; exit 0. Diagnostics remain unmodeled.
  Real tokens now have source-fidelity checks, not a claim of lexical equivalence.

Final verification: 162 flix-spec tests, `verify.sh`, `spotlessCheck`, both external
acceptance suites and the staged cold-resolution digest probe pass. JetBrains'
full language suite passes 240 tests with one compiler-protocol integration test
skipped (no `FLIX_JAR` supplied); this is not a claim of compiler protocol coverage.
Its corpus/pin checks ran against an isolated local checkout of the exact
`40949531b4d42e5eaf2e4b9997537eaf793c24e7` revision: 428 files, one deliberate
exclusion, **427 clean**, zero crashes or lossy parses. Workflow structure and the
new fault-injection shell script validate; full workflow shell lint still reports
pre-existing quoting warnings in the unrelated release-draft steps.

### Exact staged candidate

Local Maven repository: `build/release-candidate-0.77.4`. Code revision `411bd32`.
Consumer data remains independently pinned at 0.77.2 (tree-sitter) and 0.75.8
(JetBrains); the staged data 0.77.4 is also tested by the distribution acceptance
suites. SHA-256 of the exact candidate artifacts:

```text
flix-spec-0.77.4.jar
ac391c5ff1728aad1ab64b5932faabcc74bcdf1badd7f14b6bdabf593a5a6f26
flix-spec-runner-0.77.4.jar
c378173c89deea99324e6ef76890dab805e5cc7272f4c701a8caba4ea72942df
flix-spec-gradle-plugin-0.77.4.jar
0f28a0eff272e7d1b2d713f955e4a63c810ce5b852316eda3106d07c964e1615
io.github.wstein.flix-spec.gradle.plugin-0.77.4.pom
245db20a59076839c9cea615bb1f728acce2d35d820b4164966d84527d77cd36
```

Reproduce the cold-resolution identity check (Java 21):

```sh
FLIXSPEC_MAVEN_URL="file://$PWD/build/release-candidate-0.77.4" \
  bash tools/packaging/verify-published.sh --require-digest 0.77.4
bash tools/runner/acceptance.sh build/release-candidate-0.77.4 0.77.4
bash tools/gradle-plugin/acceptance.sh build/release-candidate-0.77.4 0.77.4
```

Consumer reproduction commands live in tree-sitter's `conformance/RUNNER-PILOT.md`
and JetBrains' `conformance/README.md`. Pass the staged runner jar to tree-sitter
and `-PflixSpecRepository=file:///absolute/path/to/staged-maven` to JetBrains.

### Remaining release operations (not performed)

Push flix-spec's pinned `411bd32` revision before the consumer CI change: its
checkout must resolve that exact commit. Push the consumer changes and require
the remote workflows to pass; local success is not a remote CI result. Then
review [the migration notes](VERSIONING.md#0774-consumer-migration), tag the version
matching Gradle, and let the existing publication/digest gate verify the public
repository. Do not overwrite a previously published release or treat this local
candidate's availability as evidence that Pages already serves it.

## Initial pilot (historical)

Measured 2026-09-29, in this order: **tree-sitter-flix**, then
**flix-jetbrains-plugin**. Runner/plugin 0.77.4 are local publications, not a
release. Reports use schema 9. Neither consumer's grammar or existing baselines
were changed to obtain a passing result.

| Consumer | Integration | Existing data pin | Result |
| --- | --- | --- | --- |
| tree-sitter-flix | Node adapter → executable runner → JSON + HTML | 0.77.2 | Runs; exits 1 on depth 92% below unchanged 93% floor |
| flix-jetbrains-plugin | Published Gradle plugin marker → real PSI adapter → runner → JSON + HTML | 0.75.8 | Runs; exits 1 on 13 structural differences vs baseline 4, and 6 recovery differences vs default 0 |

These are successful integration exercises, **not passing consumer conformance
gates**. Keep release adoption separate from approving new baselines.

## Tree-sitter

The consumer's `scripts/flix-spec-conformance.mjs` now accepts `--runner` (or
`FLIX_SPEC_RUNNER`) and an extracted bundle with `--spec-version` (or
`FLIX_SPEC_VERSION`). It no longer invokes a task inside a flix-spec checkout.
Java 21, Node and tree-sitter are needed; Gradle and flix.jar are not.

The exact published 0.77.2 data jar was downloaded, checked against its published
SHA-256 and extracted. The consumer still checks the compiler pin, vocabulary
digests and fixture revision. CLI 0.27.0 projected all 147 fixtures:

- Structure: 86 differences, 106/147 agree, 2002/2183 nodes, depth 92%.
- Recovery: 54 differences, 7/28 agree, depth 67%.
- Diagnostics: 7 accept/reject differences, 140/147 agree. Native diagnostic kinds
  remain unmapped; this is not kind/line fidelity.
- Source shape passes; tokens and lexical correctness remain unmeasured.

All divergence counts match the existing baseline. Only the depth floor fails:
the reference-tree denominator no longer shrinks with consumer normalization.
Review the metric migration before changing the floor. The adapter declares
structure, recovery and diagnostics, not tokens. It retains failure exit codes
and renders HTML on a comparison failure.

Consumer commit: `b4d3e0f`. Reproduction: `conformance/RUNNER-PILOT.md` in
tree-sitter-flix. Its 114 grammar tests, 3 adapter tests and lint pass.

## JetBrains

The opt-in `conformance-pilot/` standalone Gradle build resolves the published
plugin marker and runner from a supplied local Maven repository. It preserves
the consumer's 0.75.8 data pin and normal build. `check` first reruns the real
`FlixSpecConformanceTest.testFixturesParseAndProject`, then runs `flixSpecCheck`.
The producer validates exact jar identity against the plugin's resolved bundle,
clears its old JSON output, and emits raw schema-2 projections in a stable build
directory. A deliberately supplied 0.77.2 jar correctly fails the identity check.

With Gradle 9.7.1 and Java 21, all 138 fixtures were projected:

- Structure: 13 differences, 130/138 agree, depth 36%; unchanged count allowance 4.
- Recovery: 6 differences, 19/22 agree, depth 40%; no previous recovery baseline.
- Diagnostics: explicitly not applicable; this adapter emits none.
- Source shape passes; tokens and lexical correctness remain unmeasured.

The map declares structure and recovery. No depth floor is invented for this
pilot. Both the main and pilot builds reuse configuration caches; the adapter
still reruns. JSON and HTML reports are generated on the genuine exit-1 failure.

The local Kotlin comparator still reports **4 differences / 134 agreeing
fixtures** on the same parser and artifact. Its token-blind elision is the cause:
it discards token leaves before counting wrapper children, whereas the runner
retains their arity. Removing token leaves from a **temporary copy** of the
expected trees reproduces exactly 4 differences with the runner. Untouched data
reports 13. The temporary experiment is not wired into either build.

The three targeted consumer tests (projection emission, map inventory and old
comparator) pass. This is not a claim that the entire IDE suite or local Flix
checkout pin check was run. Reproduction and report paths are documented in the
consumer's `conformance-pilot/README.md` (commit `18325d6`).

## Runner defect found and fixed

The first tree-sitter run failed before comparison: a new runner validated its
consumer map using the **older data bundle's schema**, which rejected supported
`capabilities`. That contradicted independently pinned data/runner versions.

The runner now embeds and uses its own projection-map schema. Commit `73495a1`
adds a unit regression and an external acceptance case with an older map schema.
The published runner and directly built runner used in these pilots have the
same SHA-256. The runner external acceptance suite, all 161 flix-spec tests,
`tools/project/verify.sh` and `spotlessCheck` pass.

## Original adoption questions

1. Review tree-sitter's depth migration explicitly; do not silently lower 93%.
2. Review JetBrains' token-sensitive map/adapter differences and six newly measured
   recovery differences before replacing its local comparator or setting allowances.
3. Publish only after those adoption decisions are understood. Neither pilot
   changes baselines, creates accepted differences, pushes commits, or releases.
