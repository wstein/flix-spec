# Flix Spec Notice & Third-Party Provenance

This product includes software and artifacts derived from the Flix compiler project (`github.com/flix/flix`), licensed under the Apache License 2.0.

## 1. Flix Reference Compiler

<!-- generated: provenance -->
- **Upstream Repository**: https://github.com/flix/flix
- **Pinned Tag / Release**: `v0.77.0`
- **Pinned Commit SHA**: `4a5b60a31ac03bb762f68b554a0fc2b6f4d982b9`
- **Git Tree Hash**: `6a7f542f0ec2c290c56c340c2366f136517483a5`
- **Oracle Artifact SHA-256**: `20007d79f97b696ba388113e2a4235227691d33a00bfa669f2316b37a7b14201`
<!-- /generated: provenance -->
- **License**: Apache License 2.0 (http://www.apache.org/licenses/LICENSE-2.0)
- **Copyright**: Copyright (c) 2015-2026 Flix authors & University of Waterloo

The concrete syntax tree hierarchy inventory in `ast/treekind.json` and syntax tree projections are extracted directly from the compiled Flix reference compiler jar at the pinned release.

**Provenance of the oracle artifact.** The pinned `flix.jar` is the upstream release asset,
identified by SHA-256, matching the digest in the official release metadata. This pin records
no build attestation or commit stamp inside the artifact, so the chain `tag → commit → tree` is verifiable through git
while `jar → commit` is not verifiable from outside the project. `pin.json.oracleArtifact.attestation`
records this as `digest-only`. At v0.75.1, a measured same-commit rebuild was *not* byte-identical
to the published asset (16794 entries versus 16776), demonstrating why a release asset cannot be
silently replaced by a local rebuild. The historical measurements are in `docs/phase0-spike.md`.

## 2. Test Fixtures Adapted from tree-sitter-flix

Most fixtures under `fixtures/positive/` and `fixtures/negative/` are Flix source snippets adapted
from the test corpus of [`wstein/tree-sitter-flix`](https://github.com/wstein/tree-sitter-flix),
used under the MIT License.

    MIT License
    Copyright (c) 2026 Werner Stein

Only the Flix source of each corpus entry was taken; the tree-sitter s-expression expectations were
not. The expected trees in `fixtures/expected/` are generated from the pinned Flix reference
compiler and are not derived from tree-sitter-flix.

## 3. Derived Artifacts

All test fixtures and corpus specifications derived from Flix source code inherit the Apache 2.0 license of the upstream Flix project.

## 4. Build and Verification Tooling

`flix-spec` never compiles Flix; it builds against the pinned jar as an external dependency. Its
own tooling depends only on the Scala standard library (`org.scala-lang:scala-library`, Apache 2.0),
Gradle plugins declared in `build.gradle.kts`, and test-scope libraries (ScalaTest, JUnit). No
third-party grammar, lexer base, or parser generator is vendored into this repository.
