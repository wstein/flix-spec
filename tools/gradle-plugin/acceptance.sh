#!/usr/bin/env bash
# Test the published plugin marker and implementation, not TestKit's injected classpath.
set -euo pipefail
REPO="${1:?usage: acceptance.sh MAVEN_REPOSITORY VERSION}"
VERSION="${2:?version required}"
REPO="$(cd "$REPO" && pwd)"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
cp "$ROOT/examples/gradle-consumer/settings.gradle.kts" "$WORK/"
cp "$ROOT/examples/gradle-consumer/build.gradle.kts" "$WORK/"
cd "$WORK"
for attempt in 1 2; do
  "$ROOT/gradlew" --project-dir "$WORK" --configuration-cache check \
    "-PflixSpecRepository=file://$REPO" "-PflixSpecPluginVersion=$VERSION" \
    "-PflixSpecRunnerVersion=$VERSION" "-PflixSpecDataVersion=$VERSION" > "run-$attempt.log" 2>&1 || {
      cat "run-$attempt.log" >&2
      exit 1
    }
done
grep -q 'Reusing configuration cache' run-2.log
grep -q 'flixSpecCheck UP-TO-DATE' run-2.log
test -s build/reports/flix-spec/report.html
jq -e '.lanes.oracle_conformance.verdict == "pass"' build/reports/flix-spec/report.json >/dev/null
echo 'OK: published Gradle plugin resolves via its marker and checks a consumer outside the checkout'
