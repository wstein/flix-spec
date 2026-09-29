# Standalone conformance runner

The comparison and its tests live in `tools/conformance`. This module depends on
Scala's standard library, not the Flix oracle or the extraction module. The
dependency points in one direction: `tools/project` uses `tools/conformance`.
An accidental reference back to an extractor therefore fails compilation.

The existing `:tools:project:conformance` task remains a compatibility entry point.
Develop the comparison independently with `./gradlew :tools:conformance:test`.
