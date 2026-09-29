# Standalone conformance runner

The comparison and its tests live in `tools/conformance`. This module depends on
Scala's standard library, not the Flix oracle or the extraction module. The
dependency points in one direction: `tools/project` uses `tools/conformance`.
An accidental reference back to an extractor therefore fails compilation.

The existing `:tools:project:conformance` task remains a compatibility entry point.
Develop the comparison independently with `./gradlew :tools:conformance:test`.

## Distribution and interface

`./gradlew :tools:conformance:runnerJar` produces
`tools/conformance/build/libs/flix-spec-runner-<version>.jar`. It includes the Scala
runtime and needs only Java 21 or newer. The separate Maven coordinate is
`io.github.wstein:flix-spec-runner:<version>` in the same repository as the data
bundle; choose runner and data versions independently. `:tools:conformance:publish`
uses `-PflixSpec.publishRepo=<repository URI>` (a local build directory by default).
Publishing to Pages happens in CI, alongside the data artifact.

```sh
java -jar flix-spec-runner.jar --supported-schemas
java -jar flix-spec-runner.jar --spec-root /path/to/extracted-bundle \
  --source-root /path/to/consumer --actual /path/to/output \
  --map /path/to/projection-map.json --report /path/to/report.json
```

Input schema versions are checked before comparison. The embedded
`supported-schemas.json`, also available through `--supported-schemas`, declares
the exact versions accepted and the report version emitted. Unsupported or
malformed inputs exit **2**, conformance failures exit **1**, and success exits
**0**. Paths supplied as options are relative to the caller's working directory;
source paths inside projections are relative to `--source-root`, which defaults
to `--spec-root`. No oracle, Gradle installation, or repository checkout is needed.

The supported interface is the CLI plus versioned JSON, not the Scala classes.
Run `--help` for comparison thresholds and accepted-difference options. Accepted
sets remain consumer-owned and bound to the fixture revision.
Consumer maps are validated against the runner's embedded schema, so upgrading
the runner can enable capabilities without upgrading an otherwise compatible
data bundle. See the [real consumer qualification](CONSUMER-PILOTS.md) and
[migration notes](VERSIONING.md#0774-consumer-migration) before re-recording floors.

## Distribution acceptance test

```sh
./gradlew :packaging:publishFlixSpecPublicationToFlixSpecRepoRepository \
  :tools:conformance:publishRunnerPublicationToFlixSpecRepoRepository \
  "-PflixSpec.publishRepo=file://$PWD/build/runner-acceptance-repo"
bash tools/runner/acceptance.sh build/runner-acceptance-repo "$(sed -n 's/^version=//p' gradle.properties)"
```

CI runs this against the actual Maven publication. It copies only the two jars and
the [example adapter](../examples/consumer/README.md) into a fresh temporary
directory. All four lanes must pass, structural mutations and capability silence
must fail, and unsupported schemas and malformed invocations must exit 2.

## HTML reports

```sh
java -jar flix-spec-runner.jar render --report report.json --html report.html
```

Rendering is a separate command, so it works for failed comparisons as well as
passing ones. It validates the report version declared by `--supported-schemas` and writes self-contained,
offline HTML with no JavaScript or external resources. All consumer content is
escaped. Each lane retains its verdict, caveats, missing fixtures, unmapped names,
checks and divergence sample; capped samples are labelled explicitly. The HTML
does not replace the runner's exit status or merge the lanes into a score.

## Adapter scaffolding

```sh
java -jar flix-spec-runner.jar init --directory conformance --consumer my-parser
```

This creates a README, an intentionally failing parser placeholder, fixture
production and check scripts, and a consumer-owned projection map. Existing
directories are never overwritten. Production refuses stale output directories;
checks preserve exit status and render HTML even after a conformance failure.
Implement the parser and mappings yourself, explicitly declare capabilities, and
review comparison depth before adding this to CI. No differences are accepted
automatically. The generated scripts require Bash and jq.
