#!/usr/bin/env bash
# Packages already assembled source data; does not build or publish artifacts.
set -euo pipefail
if [[ $# -ne 4 ]]; then
  echo 'usage: assemble-release-bundle.sh ASSEMBLED README OUTPUT VERSION' >&2
  exit 2
fi
ASSEMBLED="$1"
README="$2"
OUTPUT="$3"
VERSION="$4"
case "$VERSION" in
  ''|*[!a-zA-Z0-9._+-]*) echo 'FATAL: invalid bundle version' >&2; exit 2 ;;
esac
BUNDLE="flix-spec-${VERSION#v}"
# Maven and the release tar consume the same assembleArtifacts Sync output.
# Check load-bearing data before copying so an omitted input cannot ship silently.
for required in fixtures/raw fixtures/expected ast/transparency.json profiles/profiles.json; do
  test -e "${ASSEMBLED}/${required}" \
    || { echo "FATAL: release bundle is missing ${required}" >&2; exit 1; }
done
mkdir -p "$OUTPUT"
test ! -e "${OUTPUT}/${BUNDLE}" \
  || { echo "FATAL: bundle destination already exists: ${OUTPUT}/${BUNDLE}" >&2; exit 1; }
mkdir "${OUTPUT}/${BUNDLE}"
cp -R "${ASSEMBLED}/." "${OUTPUT}/${BUNDLE}/"
cp "$README" "${OUTPUT}/${BUNDLE}/README.md"
tar -czf "${OUTPUT}/${BUNDLE}.tar.gz" -C "$OUTPUT" "$BUNDLE"
( cd "$OUTPUT" && sha256sum "${BUNDLE}.tar.gz" > SHA256SUMS )
( cd "${OUTPUT}/${BUNDLE}" && find . -type f -print0 \
    | sort -z | xargs -0 sha256sum > ../SHA256SUMS.contents )
cat "${OUTPUT}/SHA256SUMS"
