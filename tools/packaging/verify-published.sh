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
# Usage: verify-published.sh [--require-digest] <version>
#
# --require-digest makes the identity comparison mandatory: no local build of that version is a
# failure rather than a downgrade to the content check. CI passes it, because there the jar was just
# built in the same job and its absence means something is wrong with the job, not with the request.
# Without it the comparison is best-effort, which is what a human checking an older published version
# from a clean tree needs -- there is nothing local to compare and that is not a fault.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

REPO_URL="${FLIXSPEC_MAVEN_URL:-https://wstein.github.io/flix-spec/maven/}"
GROUP="io.github.wstein"
# Every coordinate a publish produces, with where this checkout builds it. The gate used to name only the
# data bundle, while `pages.yml` publishes three artifacts plus the plugin marker -- so a release that omitted
# the runner and the plugin entirely reported success, and a missing marker would break
# `plugins { id("io.github.wstein.flix-spec") }` at settings evaluation with nothing to notice.
ARTIFACTS=(
  "flix-spec:packaging/build/distributions"
  "flix-spec-runner:tools/conformance/build/libs"
  "flix-spec-gradle-plugin:tools/gradle-plugin/build/libs"
)
# The Gradle plugin marker is a POM-only coordinate under its own group; it is what `plugins { id(...) }`
# resolves before it ever reaches the implementation jar.
MARKER_GROUP="io.github.wstein.flix-spec"
MARKER_ARTIFACT="io.github.wstein.flix-spec.gradle.plugin"

REQUIRE_DIGEST=0
VERSION=""
for arg in "$@"; do
  case "$arg" in
    --require-digest) REQUIRE_DIGEST=1 ;;
    -*) echo "unknown option: $arg" >&2; exit 2 ;;
    *) VERSION="$arg" ;;
  esac
done

if [ -z "$VERSION" ]; then
  echo "usage: verify-published.sh [--require-digest] <version>" >&2
  exit 2
fi

# Each coordinate is probed in its own Gradle project below; this loop drives them.
for entry in "${ARTIFACTS[@]}"; do
ARTIFACT="${entry%%:*}"
LOCAL_DIR="${entry##*:}"
# Only the data bundle has a documented interior. The runner and plugin jars are code, so "resolves and is
# byte-identical to what we built" is the whole question for them.
if [ "$ARTIFACT" = "flix-spec" ]; then
  CONTENT_REQUIRED='"pin.json", "ast/treekind.json", "ast/tokenkind.json"'
else
  CONTENT_REQUIRED='"META-INF/MANIFEST.MF"'
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
LOCAL_JAR="$ROOT/$LOCAL_DIR/$ARTIFACT-$VERSION.jar"
EXPECTED_SHA=""
if [ -f "$LOCAL_JAR" ]; then
  EXPECTED_SHA="$(shasum -a 256 "$LOCAL_JAR" | cut -d' ' -f1)"
  echo "Comparing against locally built $ARTIFACT-$VERSION.jar ($EXPECTED_SHA)"
elif [ "$REQUIRE_DIGEST" -eq 1 ]; then
  echo "FATAL: --require-digest was given but there is no local build at $LOCAL_JAR." >&2
  echo "       In CI the jar was built in this job, so its absence means the job is wrong, not the" >&2
  echo "       request. Falling back to the content check here would leave the identity of the" >&2
  echo "       published artifact unverified while still reporting success." >&2
  exit 1
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
        listOf($CONTENT_REQUIRED).forEach { required ->
            require(entries.contains(required)) { "published jar is missing \$required" }
        }
        println("CONTENTS ok: \${entries.size} entries")

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
  echo "OK: $GROUP:$ARTIFACT:$VERSION resolves and is the artifact built here"
else
  echo "OK: $GROUP:$ARTIFACT:$VERSION resolves (digest not compared)"
fi

done

# The plugin marker is POM-only and has no jar to compare, but it is what `plugins { id(...) }` resolves
# first: without it the id is unresolvable and the implementation jar is never reached.
MARKER_URL="${REPO_URL%/}/${MARKER_GROUP//.//}/$MARKER_ARTIFACT/$VERSION/$MARKER_ARTIFACT-$VERSION.pom"
if [[ "$MARKER_URL" == file://* ]]; then
  [ -f "${MARKER_URL#file://}" ] && MARKER_OK=yes || MARKER_OK=no
else
  [ "$(curl -s -o /dev/null -w '%{http_code}' -L "$MARKER_URL")" = "200" ] && MARKER_OK=yes || MARKER_OK=no
fi
if [ "$MARKER_OK" != "yes" ]; then
  echo "FATAL: the Gradle plugin marker $MARKER_GROUP:$MARKER_ARTIFACT:$VERSION is not served." >&2
  echo "       plugins { id } resolves this before the implementation jar, so the plugin is unusable" >&2
  echo "       even though its own jar published." >&2
  exit 1
fi
echo "OK: the Gradle plugin marker resolves"
echo "OK: all published coordinates for $VERSION verified"
