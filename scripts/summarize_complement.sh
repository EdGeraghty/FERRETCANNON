#!/usr/bin/env bash
# summarize_complement.sh
# Produces a compact JSON summary of complement-output.json with counts and failed test names.

set -euo pipefail

WORKDIR="${1:-.}"
INPUT="$WORKDIR/complement-output.json"
OUT="$WORKDIR/complement-summary.json"
OUTTXT="$WORKDIR/complement-summary.txt"

if [ ! -f "$INPUT" ]; then
  echo "{}" > "$OUT"
  exit 0
fi

# Ensure jq is available
if ! command -v jq >/dev/null 2>&1; then
  echo "jq required" >&2
  exit 2
fi

# Parse and produce summary
jq -R -s '
  split("\n") |
  map(try fromjson catch null) |
  map(select(.!=null and .Test != null)) |
  group_by(.Test) |
  map(.[-1]) as $last |
  {
    pass: ($last | map(select(.Action=="pass")) | length),
    fail: ($last | map(select(.Action=="fail")) | length),
    skip: ($last | map(select(.Action=="skip")) | length),
    failed_tests: ($last | map(select(.Action=="fail") | .Test) | unique)
  }
' "$INPUT" > "$OUT"

# Make sure file exists
if [ -f "$OUT" ]; then
  echo "Wrote $OUT"
else
  echo "Failed to write summary" >&2
  exit 3
fi

# Produce a compact text file for quick human scanning
jq -r '
  "Passed: \(.pass)\nFailed: \(.fail)\nSkipped: \(.skip)\n\nFailed tests:\n" + ( .failed_tests | map("- " + .) | join("\n") )
' "$OUT" > "$OUTTXT"

if [ -f "$OUTTXT" ]; then
  echo "Wrote $OUTTXT"
else
  echo "Failed to write text summary" >&2
  exit 4
fi
