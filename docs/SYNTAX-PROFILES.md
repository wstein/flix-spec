# Historical syntax profiles

`profiles/profiles.json` selects ten exact official releases from 0.75.0 through
0.79.0. These are **bounded parser compatibility cases**, not full historical
grammars or historical conformance suites. The current `pin.json`, root fixtures,
normalization and inventory remain a separate contract.

Each profile pins the upstream commit, four Git blob identities for SyntaxTree,
TokenKind, Parser2 and Lexer, and the SHA-256 of the official release asset.
Source identities were read from the corresponding upstream release commit.
Asset digests identify downloaded bytes; upstream does not provide a build
attestation tying those bytes to the source commit. No upstream build is needed.

Nine shared source probes run against every selected release: a baseline
function, `law`, `lawful`, effect type parameters, tight `use Foo::{...}`,
a complex extensible row tail, comments, and invalid lexer and parser sentinels. Some probes intentionally fail in older
releases. Parse acceptance and the complete ordered tree of kind names and token
texts, together with diagnostic class names, are committed separately for each
release in `profiles/expected/<version>/`. Positions and rendered diagnostics
are outside this limited snapshot contract. Error tokens are named `Err`,
without embedding their exception or absolute source path. Rejection can contain recovery trees.

The law cases preserve the pre-0.75.2 vocabulary as executable expectations.
The other cases expose changes around effect parameters (0.76), tight `::`
(0.76.2), row tails (0.78), and comment trees (0.79). A case's measured output,
including rejection, is authoritative for that case; its name does not promise
coverage of an entire feature. The cases do not test type checking or semantics.
No historical tree is validated against the current inventory or transparency.

Run offline data validation and list exact selectors:

```sh
./gradlew -q :tools:project:syntaxProfiles --args='--check'
./gradlew -q :tools:project:syntaxProfiles --args='list'
```

Download an official asset from the profile's `oracleUrl` into a local path, then
verify that release's nine committed cases. The tool checks its digest before
executing any compiler code. It never downloads an asset automatically.

```sh
./gradlew -q :tools:project:syntaxProfiles --args='--profile 0.75.1 --verify /absolute/path/flix-0.75.1.jar'
./gradlew -q :tools:project:syntaxProfiles --args='--profile 0.75.1 --oracle /absolute/path/flix-0.75.1.jar --source profiles/sources/law.flix'
```

The Scala API is `SyntaxProfiles.select(root, version)`,
`validate(root)`, `verify(root, version, jar)` and
`probe(root, version, jar, source)`. Floating aliases such as `latest` are rejected.
`validate` checks schema, exact release/case/source identities, local source and
snapshot digests, and nonempty root trees; it does not re-run historical parsers.
`verify` re-runs every case in the selected official parser and compares the full
snapshot bytes. A data-only validation pass is not a historical runtime pass.

Every parse runs in a separate JVM with only the worker classes and the selected
fat jar on its system classpath. The worker uses reflection for changed Source,
ReadAst and thread pool APIs; it links no current Flix classes. This avoids both
accidentally mixing the current oracle into an old profile and the empty-tree
failure caused by URLClassLoader isolation described in `phase0-spike.md`.
The worker drives only Lexer and Parser2, using an in-memory ReadAst root with
UTF-8 source. Reader, package loading and the standard library are outside scope.

The schema retains Draft 7 size restrictions. The shared project validator
implements a subset of Draft 7; the exact ten releases, four source keys and nine
case identities are additionally enforced in Scala, including rejection of extra
entries. Unit mutations cover an eleventh profile, missing/extra source keys,
cross-profile snapshots, source/output corruption, malformed tokens, empty trees,
and inconsistent acceptance. Both invalid sentinels must retain their measured
lexer/parser diagnostic classes in every release.
