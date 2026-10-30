#!/usr/bin/env bash
# Renders every diagram in this folder with the d2 version CI checks against.
# A different version draws different SVGs, so CI would fail: this script refuses to run with one.
set -euo pipefail
readonly WANT=v0.7.1
cd "$(dirname "$0")"

have=$(d2 --version 2>/dev/null || true)
if [[ $have != "$WANT"* ]]; then
  echo "d2 ${have:-not found}, need $WANT: go install oss.terrastruct.com/d2@$WANT" >&2
  exit 1
fi

for f in *.d2; do
  [[ $f == style.d2 ]] && continue
  d2 fmt "$f"
  d2 "$f" "${f%.d2}.svg"
done
