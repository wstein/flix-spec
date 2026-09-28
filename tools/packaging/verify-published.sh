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

# The digest of the artifact this checkout builds, when it builds this version. Resolution plus a
# content check only ever proves that *something* plausible is being served -- an older snapshot under
# the same coordinate satisfies both, which is the failure this script exists to catch one layer down.
# Comparing digests is the only form of the question that distinguishes "published" from "published
# and actually the thing we built". Requires the reproducible-jar settings in packaging/build.gradle.kts.
LOCAL_JAR="$ROOT/packaging/build/distributions/$ARTIFACT-$VERSION.jar"
EXPECTED_SHA=""
if [ -f "$LOCAL_JAR" ]; then
  EXPECTED_SHA="$(shasum -a 256 "$LOCAL_JAR" | cut -d' ' -f1)"
  echo "Comparing against locally built $ARTIFACT-$VERSION.jar ($EXPECTED_SHA)"
else
  echo "NOTE: no local build of $VERSION at $LOCAL_JAR; checking contents only." >&2
  echo "      Run ./gradlew :packaging:artifactsJar first to compare digests." >&2
fi

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

        // The identity check. Contents above are a shape; this is the artifact.
        val expected = "$EXPECTED_SHA"
        if (expected.isEmpty()) {
            println("DIGEST skipped: no local build to compare against")
        } else {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(jar.readBytes())
                .joinToString("") { "%02x".format(it) }
            require(digest == expected) {
                "served artifact is not the one built here\n" +
                    "  built:  \$expected\n" +
                    "  served: \$digest\n" +
                    "  The coordinate resolves, so this is a stale or superseded snapshot being served."
            }
            println("DIGEST ok: served artifact is byte-identical to the one built here")
        }
    }
}
EOF

# --no-daemon and the isolated home together keep this honest across repeated runs.
# Two failures are possible here and they have different causes, so they must not share a message.
# Reporting an identity mismatch as "not resolvable" sends the reader to look at Pages configuration
# for an artifact that resolved perfectly well and was simply the wrong bytes.
PROBE_LOG="$WORK/probe.log"
if ! ( cd "$WORK/probe" && "$ROOT/gradlew" --no-daemon --quiet resolveProbe 2>&1 ) | tee "$PROBE_LOG"; then
  if grep -q "served artifact is not the one built here" "$PROBE_LOG"; then
    echo "FATAL: $GROUP:$ARTIFACT:$VERSION resolves, but the served artifact is not the one built here." >&2
    echo "       Serving is fine; the identity is wrong. Either an older artifact is still being" >&2
    echo "       served under this coordinate, or this checkout does not build what was published." >&2
  else
    echo "FATAL: $GROUP:$ARTIFACT:$VERSION is not resolvable from $REPO_URL" >&2
    echo "       The publish reported success, so the failure is in serving, not in building." >&2
  fi
  exit 1
fi

if [ -n "$EXPECTED_SHA" ]; then
  echo "OK: $VERSION resolves, carries the expected contents, and is the artifact built here"
else
  echo "OK: $VERSION resolves and carries the expected contents (digest not compared)"
fi
