# Pre-release consumer pilots

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

## Before default adoption

1. Review tree-sitter's depth migration explicitly; do not silently lower 93%.
2. Review JetBrains' token-sensitive map/adapter differences and six newly measured
   recovery differences before replacing its local comparator or setting allowances.
3. Publish only after those adoption decisions are understood. Neither pilot
   changes baselines, creates accepted differences, pushes commits, or releases.
