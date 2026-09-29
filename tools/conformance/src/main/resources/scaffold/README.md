# @CONSUMER@ adapter starter

Requires Java 21+, Bash and jq. Commit this directory in your consumer repository.
Pin the runner version and the data-bundle version independently in your build.

1. Implement `adapter.sh`: parse `$1` (an absolute file path), and emit a single
   projection-schema-v2 JSON document to stdout. Preserve `$2` as `units[0].source`.
   Include `schemaVersion: 2`, `form: "raw"`, and a `units` array with the source,
   diagnostics and tree. See the bundle's `schemas/projection.schema.json` for the
   complete contract, including provenance fields. Native kind names are allowed.
2. Populate `projection-map.json` with your own node names. Declare capabilities
   you implement: `structure`, `tokens`, `diagnostics`, `recovery`. Do not declare
   capabilities merely to improve a score. Recovery also needs `recoveryMarkers`.
3. Run `bash produce.sh /path/to/spec fresh-output`. It enumerates the exact
   expected fixture filenames and invokes your adapter once per source. It refuses
   an existing output directory to avoid reusing stale projections. After an
   interrupted run, choose a fresh output directory.
4. Run `bash check.sh /path/to/runner.jar /path/to/spec fresh-output`. It preserves
   the runner exit status and prints the location of JSON and HTML reports.

The placeholder adapter intentionally exits 2. Empty mappings are **unfinished**,
not an implementation. Review unmapped nodes and comparison depth, not just the
verdict. Once established, use the runner's depth floors to prevent loss of scope.

No accepted differences are generated. If you adopt `--accepted`, review each
entry and bind it to the reported fixture revision. The independent example in
the flix-spec repository demonstrates wiring with renamed reference trees; it is
not evidence of an independent parser's correctness.
