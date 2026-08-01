# Flix Pin Bump Workflow and Checklist

This document describes the manual procedure for bumping the pinned upstream Flix release in `wstein/flix-spec`.

## Cadence and Policy

- **Pin Target**: `wstein/flix-spec` pins to official upstream release tags (e.g. `v0.75.1`), never moving branch refs or unreleased commit SHAs.
- **Cadence**: A pin bump is executed when upstream publishes a new release tag.
- **Automation**: Pin bumps are **never automated** or auto-merged. A pin bump requires human review and explicit verification of structural changes.

## Pin Bump Checklist

1. **Update `pin.json`**:
   - Update `upstream.tag`, `upstream.commit`, and `upstream.treeHash`.
   - Update `oracleArtifact.url`, `oracleArtifact.sha256`, `oracleArtifact.sizeBytes`, and `oracleArtifact.attestation`.
   - Update `buildProvenance` metadata (build tool versions, Scala version, JDK target).
   - Clear or update `treeKindCount` and `treeKindDigest`.

2. **Fetch and Verify Oracle Artifact**:
   ```sh
   ./tools/oracle/fetch.sh
   ```
   Ensure the downloaded jar matches the new digest recorded in `pin.json`.

3. **Regenerate AST Inventory**:
   ```sh
   ./gradlew :tools:project:generateTreeKind
   ```
   This updates `ast/treekind.json` via reflection over the new oracle jar.

4. **Regenerate Corpus & Fixtures**:
   ```sh
   ./corpus/fetch
   ./tools/project/verify.sh
   ```

5. **Run Verification and Formatter**:
   ```sh
   ./gradlew spotlessApply
   ./gradlew test
   ./tools/project/verify.sh
   ```

6. **Prepare PR Description**:
   In the pull request body, explicitly document:
   - Upstream release tag and commit range.
   - **TreeKinds added / removed / re-parented**. Any removed kind or re-parented sub-trait is breaking for consumers.
   - Fixture output diffs or diagnostic shifts.
