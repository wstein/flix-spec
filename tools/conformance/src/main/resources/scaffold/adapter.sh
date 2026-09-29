#!/usr/bin/env bash
set -euo pipefail
# Protocol: adapter.sh ABSOLUTE_SOURCE_PATH SPEC_RELATIVE_SOURCE_NAME
# Emit ONE projection-schema-v2 document to stdout; send logs to stderr.
# A syntax error is represented in diagnostics/tree, not by exiting nonzero.
# Nonzero exit means the adapter itself failed and stops fixture production.
echo 'TODO: implement your parser adapter; see README.md. No projections have been produced.' >&2
exit 2
