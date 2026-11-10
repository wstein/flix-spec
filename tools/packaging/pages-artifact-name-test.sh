#!/usr/bin/env bash
# pages.yml must upload and deploy the Pages artifact under one name that differs per run attempt.
# Artifacts belong to a workflow run, not to an attempt: a re-run keeps the first attempt's
# `github-pages` artifact, uploads a second one, and actions/deploy-pages then fails with
# "Multiple artifacts named "github-pages" were unexpectedly found for this workflow run."
# Every upload-pages-artifact step needs `name`, every deploy-pages step needs `artifact_name`,
# each directly in that step's own `with:` block and equal to the value below; at least one of each.
# Usage: pages-artifact-name-test.sh [path/to/pages.yml]
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
FILE="${1:-$ROOT/.github/workflows/pages.yml}"
# shellcheck disable=SC2016  # the GitHub expression must stay unexpanded: it is compared literally
WANT='github-pages-${{ github.run_attempt }}'

# Layout assumed: steps start with "      - " (6 spaces), `with:` at 8 spaces, its keys at 10.
set +e
awk -v want="$WANT" '
  function finish(   k) {
    if (kind == "") return
    k = (kind == "upload-pages-artifact") ? "name" : "artifact_name"
    v = (kind == "upload-pages-artifact") ? upload : deploy
    n[kind]++
    if (v == "") { print "FAIL: " kind " step " n[kind] " has no " k " directly in its with: block"; bad = 1 }
    else if (v != want) { print "FAIL: " kind " step " n[kind] " has " k ": " v ", expected " want; bad = 1 }
  }
  function value(s) { sub(/^[^:]*:[ ]*/, "", s); sub(/[ ]+$/, "", s); return s }
  /^      - / { finish(); kind = ""; upload = ""; deploy = ""; inwith = 0 }
  /^[ ]*#/ { next }
  match($0, /uses: actions\/(upload-pages-artifact|deploy-pages)@/) {
    s = substr($0, RSTART + 14); kind = substr(s, 1, index(s, "@") - 1)
  }
  /^        with:[ ]*$/ { inwith = 1; next }
  /^        [^ ]/ { inwith = 0 }
  inwith && /^          name:/ { upload = value($0) }
  inwith && /^          artifact_name:/ { deploy = value($0) }
  END {
    finish()
    if (n["upload-pages-artifact"] < 1) { print "FAIL: no upload-pages-artifact step"; bad = 1 }
    if (n["deploy-pages"] < 1) { print "FAIL: no deploy-pages step"; bad = 1 }
    if (!bad) print "ok: " n["upload-pages-artifact"] " upload and " n["deploy-pages"] " deploy step(s) use " want
    exit bad
  }
' "$FILE"
exit $?
