#!/usr/bin/env bash
# pages.yml must upload and deploy the Pages artifact under one name that differs per run attempt.
# Artifacts belong to a workflow run, not to an attempt: a re-run keeps the first attempt's
# `github-pages` artifact, uploads a second one, and actions/deploy-pages then fails with
# "Multiple artifacts named "github-pages" were unexpectedly found for this workflow run."
# Usage: pages-artifact-name-test.sh [path/to/pages.yml]
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
FILE="${1:-$ROOT/.github/workflows/pages.yml}"
WANT='github-pages-${{ github.run_attempt }}'

# Prints the value of KEY inside the step that uses ACTION (first match, up to the next step).
step_input() {
  awk -v action="$1" -v key="$2" '
    /^      - / { in_step = 0 }
    index($0, "uses: " action "@") { in_step = 1 }
    in_step && $1 == key ":" { sub(/^[^:]*:[ ]*/, ""); print; exit }
  ' "$FILE"
}

fail() { echo "FAIL: $*" >&2; exit 1; }

UPLOAD="$(step_input actions/upload-pages-artifact name)"
DEPLOY="$(step_input actions/deploy-pages artifact_name)"
[ -n "$UPLOAD" ] || fail "upload-pages-artifact sets no name (default github-pages repeats on re-run)"
[ -n "$DEPLOY" ] || fail "deploy-pages sets no artifact_name (default github-pages)"
[ "$UPLOAD" = "$DEPLOY" ] || fail "upload name '$UPLOAD' differs from deploy artifact_name '$DEPLOY'"
[ "$UPLOAD" = "$WANT" ] || fail "artifact name is '$UPLOAD', expected '$WANT'"
echo "ok: upload and deploy both use $UPLOAD"
