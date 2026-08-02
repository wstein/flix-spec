#!/usr/bin/env bash
# Verifies that a published coordinate is actually consumable, by resolving it the way a consumer
# would: from a clean Gradle project against the public URL, with no local cache.
#
# Why this exists: publishing succeeded and the artifact was unreachable for days while CI stayed
# green. `pages.yml` pushed a gh-pages branch, GitHub Pages was configured to expect an Actions
# deployment instead, and every URL under the site returned 404. Nothing checked, because the
# publish job only verified that it had *pushed* -- a proxy for the thing that matters.
#
# A resolution is not a proxy. If this passes, a consumer can add the coordinate and get the files.
#
# Usage: verify-published.sh [<version>]   (default: the version pages.yml just published)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

REPO_URL="${FLIXSPEC_MAVEN_URL:-https://wstein.github.io/flix-spec/maven/}"
GROUP="io.github.wstein"
ARTIFACT="flix-spec"
VERSION="${1:-}"

if [ -z "$VERSION" ]; then
  echo "usage: verify-published.sh <version>" >&2
  exit 2
fi

echo "Resolving $GROUP:$ARTIFACT:$VERSION from $REPO_URL"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# An isolated Gradle home: a cache hit would prove nothing about what is published.
export GRADLE_USER_HOME="$WORK/gradle-home"

mkdir -p "$WORK/probe"
cat > "$WORK/probe/settings.gradle.kts" <<EOF
rootProject.name = "flix-spec-publish-probe"
EOF

# A release version must not be probed through a snapshots-only filter, and a snapshot must not be
# probed through a releases-only one; the filter is part of what is being verified.
if [[ "$VERSION" == *SNAPSHOT* || "$VERSION" =~ [0-9]{8}\.[0-9]{6}- ]]; then
  CONTENT_FILTER="mavenContent { snapshotsOnly() }"
else
  CONTENT_FILTER="mavenContent { releasesOnly() }"
fi

cat > "$WORK/probe/build.gradle.kts" <<EOF
plugins { base }

repositories {
    maven {
        url = uri("$REPO_URL")
        content { includeGroup("$GROUP") }
        $CONTENT_FILTER
    }
}

val probe by configurations.creating

dependencies { probe("$GROUP:$ARTIFACT:$VERSION") }

tasks.register("resolveProbe") {
    val files = probe
    doLast {
        val resolved = files.resolve()
        require(resolved.isNotEmpty()) { "resolved to nothing" }
        resolved.forEach { println("RESOLVED " + it.name + " " + it.length() + " bytes") }

        // Resolution alone only proves the coordinate exists. The artifact is a data bundle, so
        // also assert it carries the files consumers actually depend on.
        val jar = resolved.first { it.name.endsWith(".jar") }
        val entries = java.util.zip.ZipFile(jar).use { z -> z.entries().toList().map { it.name } }
        listOf("pin.json", "ast/treekind.json", "ast/tokenkind.json").forEach { required ->
            require(entries.contains(required)) { "published jar is missing \$required" }
        }
        val fixtures = entries.count { it.startsWith("fixtures/") && it.endsWith(".flix") }
        require(fixtures > 0) { "published jar contains no fixtures" }
        println("CONTENTS ok: \$fixtures fixtures, inventories present")
    }
}
EOF

# --no-daemon and the isolated home together keep this honest across repeated runs.
( cd "$WORK/probe" && gradle --no-daemon --quiet resolveProbe 2>&1 ) || {
  echo "FATAL: $GROUP:$ARTIFACT:$VERSION is not resolvable from $REPO_URL" >&2
  echo "       The publish reported success, so the failure is in serving, not in building." >&2
  exit 1
}

echo "OK: $VERSION resolves and carries the expected contents"
