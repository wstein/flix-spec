#!/usr/bin/env bash
# Resolve synthetic Maven publications with real Gradle, without building or publishing the project.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
"$ROOT/gradlew" --version >/dev/null
WRAPPER_CACHE="${GRADLE_USER_HOME:-$HOME/.gradle}/wrapper"
mkdir -p "$WORK/checkout/tools/packaging" "$WORK/content/META-INF" "$WORK/content/ast"
cp "$ROOT/tools/packaging/verify-published.sh" "$WORK/checkout/tools/packaging/"
# Share only the Gradle distribution; every verifier run still gets an empty dependency cache.
cat > "$WORK/checkout/gradlew" <<EOF_WRAPPER
#!/usr/bin/env bash
set -euo pipefail
mkdir -p "\$GRADLE_USER_HOME"
[ -e "\$GRADLE_USER_HOME/wrapper" ] || ln -s "$WRAPPER_CACHE" "\$GRADLE_USER_HOME/wrapper"
exec "$ROOT/gradlew" "\$@"
EOF_WRAPPER
chmod +x "$WORK/checkout/gradlew"
printf '{}\n' > "$WORK/content/pin.json"
printf '{}\n' > "$WORK/content/ast/treekind.json"
printf '{}\n' > "$WORK/content/ast/tokenkind.json"
printf 'Manifest-Version: 1.0\n' > "$WORK/content/META-INF/MANIFEST.MF"
jar --create --file "$WORK/artifact.jar" -C "$WORK/content" .
GROUP=io.github.wstein
MARKER=io.github.wstein.flix-spec.gradle.plugin
MARKER_GROUP=io.github.wstein.flix-spec

fixture() {
  VERSION="$1"
  REPO="$WORK/repo-$VERSION"
  rm -rf "$REPO"
  VALUE="$VERSION"
  if [[ "$VERSION" == *-SNAPSHOT ]]; then VALUE="${VERSION%-SNAPSHOT}-20261109.125843-3"; fi
  for entry in flix-spec:packaging/build/distributions flix-spec-runner:tools/conformance/build/libs flix-spec-gradle-plugin:tools/gradle-plugin/build/libs "$MARKER:marker"; do
    artifact="${entry%%:*}"; local_dir="${entry#*:}"; group="$GROUP"
    if [ "$artifact" = "$MARKER" ]; then group="$MARKER_GROUP"; fi
    dest="$REPO/${group//.//}/$artifact/$VERSION"
    mkdir -p "$dest"
    dependency=''
    if [ "$artifact" = "$MARKER" ]; then
      dependency="<dependencies><dependency><groupId>$GROUP</groupId><artifactId>flix-spec-gradle-plugin</artifactId><version>$VERSION</version></dependency></dependencies>"
    else
      cp "$WORK/artifact.jar" "$dest/$artifact-$VALUE.jar"
      mkdir -p "$WORK/checkout/$local_dir"
      cp "$WORK/artifact.jar" "$WORK/checkout/$local_dir/$artifact-$VERSION.jar"
    fi
    printf '<project><modelVersion>4.0.0</modelVersion><groupId>%s</groupId><artifactId>%s</artifactId><version>%s</version><packaging>%s</packaging>%s</project>\n' "$group" "$artifact" "$VERSION" "$([ "$artifact" = "$MARKER" ] && echo pom || echo jar)" "$dependency" > "$dest/$artifact-$VALUE.pom"
    if [[ "$VERSION" == *-SNAPSHOT ]]; then
      printf '<metadata><groupId>%s</groupId><artifactId>%s</artifactId><version>%s</version><versioning><snapshot><timestamp>20261109.125843</timestamp><buildNumber>3</buildNumber></snapshot><lastUpdated>20261109125843</lastUpdated><snapshotVersions><snapshotVersion><extension>pom</extension><value>%s</value><updated>20261109125843</updated></snapshotVersion><snapshotVersion><extension>jar</extension><value>%s</value><updated>20261109125843</updated></snapshotVersion></snapshotVersions></versioning></metadata>\n' "$group" "$artifact" "$VERSION" "$VALUE" "$VALUE" > "$dest/maven-metadata.xml"
    fi
  done
  MARKER_POM="$REPO/${MARKER_GROUP//.//}/$MARKER/$VERSION/$MARKER-$VALUE.pom"
}

probe() {
  local expected="$1" name="$2"
  if FLIXSPEC_MAVEN_URL="file://$REPO" bash "$WORK/checkout/tools/packaging/verify-published.sh" --require-digest "$VERSION" > "$WORK/$name.log" 2>&1; then result=0; else result=$?; fi
  if [ "$expected" = success ]; then
    if [ "$result" -ne 0 ]; then cat "$WORK/$name.log"; exit 1; fi
    grep -q 'OK: all published coordinates' "$WORK/$name.log"
  else
    if [ "$result" -eq 0 ]; then echo "FAIL: $name accepted"; exit 1; fi
    grep -q "$expected" "$WORK/$name.log" || { cat "$WORK/$name.log"; exit 1; }
  fi
  echo "PASS: $name"
}

fixture 1.0-SNAPSHOT
probe success timestamped-snapshot
[ "${1:-}" != snapshot-only ] || exit 0
fixture 1.0
probe success release
fixture 1.0-SNAPSHOT
rm "$MARKER_POM"
probe 'not resolvable' missing-marker
fixture 1.0-SNAPSHOT
printf '<broken' > "$MARKER_POM"
probe 'not resolvable' malformed-marker
fixture 1.0-SNAPSHOT
# A POM that exists but has no implementation dependency must not pass.
printf '<project><modelVersion>4.0.0</modelVersion><groupId>%s</groupId><artifactId>%s</artifactId><version>%s</version><packaging>pom</packaging></project>' "$MARKER_GROUP" "$MARKER" "$VERSION" > "$MARKER_POM"
probe 'resolved to nothing' disconnected-marker
fixture 1.0-SNAPSHOT
# Point the marker at an unavailable implementation, while all direct jar probes remain valid.
perl -pi -e 's/<artifactId>flix-spec-gradle-plugin<\//<artifactId>missing-plugin<\//' "$MARKER_POM"
probe 'not resolvable' missing-marker-target
fixture 1.0-SNAPSHOT
perl -pi -e 's/<artifactId>flix-spec-gradle-plugin<\//<artifactId>flix-spec-runner<\//' "$MARKER_POM"
probe 'missing expected module' wrong-marker-target
fixture 1.0-SNAPSHOT
printf 'different bytes' > "$WORK/content/extra"
jar --create --file "$REPO/io/github/wstein/flix-spec-gradle-plugin/$VERSION/flix-spec-gradle-plugin-$VALUE.jar" -C "$WORK/content" .
probe 'served artifact is not the one built here' stale-implementation
fixture 1.0-SNAPSHOT
rm "$REPO/io/github/wstein/flix-spec-gradle-plugin/$VERSION/flix-spec-gradle-plugin-$VALUE.jar"
probe 'not resolvable' missing-implementation
fixture 1.0-SNAPSHOT
# A valid marker can select a different version of the right module: compare those bytes, too.
old="$REPO/io/github/wstein/flix-spec-gradle-plugin/0.9-SNAPSHOT"
mkdir -p "$old"
jar --create --file "$old/flix-spec-gradle-plugin-0.9-SNAPSHOT.jar" -C "$WORK/content" .
printf '<project><modelVersion>4.0.0</modelVersion><groupId>%s</groupId><artifactId>flix-spec-gradle-plugin</artifactId><version>0.9-SNAPSHOT</version></project>' "$GROUP" > "$old/flix-spec-gradle-plugin-0.9-SNAPSHOT.pom"
perl -pi -e 's|<artifactId>flix-spec-gradle-plugin</artifactId><version>1.0-SNAPSHOT</version>|<artifactId>flix-spec-gradle-plugin</artifactId><version>0.9-SNAPSHOT</version>|' "$MARKER_POM"
probe 'served artifact is not the one built here' stale-marker-implementation
