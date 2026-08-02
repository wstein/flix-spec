#!/usr/bin/env bash
# Reports whether the oracle has moved in a way that matters, without cloning or building it.
#
# Why this exists: the previous staleness check compared upstream's latest *release tag* against
# pin.json's. That is a proxy, and it failed exactly as proxies do. At the time this script was
# written, upstream had removed the `Law` TreeKind and the `KeywordLaw`/`KeywordLawful` TokenKinds
# on master while `v0.75.1` was still the newest release -- so the tag check reported "pin is
# current" while the vocabulary the whole repository is built on had already changed.
#
# This compares the blobs that actually define that vocabulary and the parser's behaviour. Both
# checks are cheap: two-to-four GitHub API calls, no checkout, no jar.
#
# Deliberately over-approximate: a comment-only edit to one of these files reports as drift. That
# is the right bias for a notice -- a false alarm costs a glance, a false silence costs a stale
# pin nobody noticed. It never fails the build; upstream moving is not our failure.
#
# Exit status is 0 whether or not drift is found. Use --strict to exit 1 on drift.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

STRICT=0
REF="master"
for arg in "$@"; do
  case "$arg" in
    --strict) STRICT=1 ;;
    --ref=*) REF="${arg#--ref=}" ;;
    *) echo "usage: check-drift.sh [--strict] [--ref=<git-ref>]" >&2; exit 2 ;;
  esac
done

REPO="$(jq -r '.upstream.repository' pin.json | sed 's#https://github.com/##')"
PINNED_TAG="$(jq -r '.upstream.tag' pin.json)"
PINNED_COMMIT="$(jq -r '.upstream.commit' pin.json)"

if [ "$(jq -r '.upstream.vocabularySources // empty' pin.json)" = "" ]; then
  echo "FATAL: pin.json has no upstream.vocabularySources to compare against" >&2
  exit 1
fi

echo "pinned: $PINNED_TAG ($PINNED_COMMIT)"
echo "against: $REPO@$REF"
echo

drift=0
while read -r path recorded; do
  actual="$(gh api "repos/$REPO/contents/$path?ref=$REF" --jq '.sha' 2>/dev/null || echo "")"
  name="$(basename "$path")"
  if [ -z "$actual" ]; then
    # A file that vanished upstream is itself drift, and a loud kind.
    echo "  $name: GONE or unreadable at $REF (was ${recorded:0:12})"
    drift=$((drift + 1))
  elif [ "$actual" != "$recorded" ]; then
    echo "  $name: CHANGED  pin=${recorded:0:12} $REF=${actual:0:12}"
    drift=$((drift + 1))
  else
    echo "  $name: unchanged"
  fi
done < <(jq -r '.upstream.vocabularySources | to_entries[] | "\(.key) \(.value)"' pin.json)

echo
LATEST="$(gh release view --repo "$REPO" --json tagName --jq '.tagName' 2>/dev/null || echo "")"
if [ -n "$LATEST" ] && [ "$LATEST" != "$PINNED_TAG" ]; then
  echo "A newer release exists: $LATEST (pinned: $PINNED_TAG)"
fi

if [ "$drift" -eq 0 ]; then
  echo "No drift: the oracle's parser and vocabulary sources are unchanged at $REF."
  exit 0
fi

echo "DRIFT: $drift oracle source file(s) changed at $REF."
echo "The pin is behind in a way that can change the generated inventories or tree shapes."
echo "See docs/PIN-BUMP.md. Bumping is a reviewed human decision, never automatic."

if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
  {
    echo "### Oracle drift detected"
    echo
    echo "\`$REPO@$REF\` differs from the pin in $drift parser/vocabulary source file(s)."
    echo "Pinned: \`$PINNED_TAG\` (\`${PINNED_COMMIT:0:12}\`)."
    echo
    echo "This is a notice, not a failure. See \`docs/PIN-BUMP.md\`."
  } >> "$GITHUB_STEP_SUMMARY"
fi

[ "$STRICT" -eq 1 ] && exit 1
exit 0
