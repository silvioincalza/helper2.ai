#!/usr/bin/env bash
set -euo pipefail
readonly WANT=v0.9.0
cd "$(dirname "$0")"

have=$(d2 --version 2>/dev/null || true)
if [[ $have != "$WANT" ]]; then
  echo "d2 ${have:-not found}, need $WANT. Install it with:" >&2
  echo "  brew install d2   (macOS, when Homebrew has $WANT)" >&2
  echo "  go install -ldflags \"-X github.com/d2lang/d2/lib/version.Version=$WANT\" github.com/d2lang/d2@$WANT" >&2
  exit 1
fi

for f in *.d2; do
  [[ $f == style.d2 ]] && continue
  d2 fmt "$f"
  d2 "$f" "${f%.d2}.svg"
done

orphans=0
for s in *.svg; do
  [[ $s == hexagonal-architecture.svg ]] && continue
  if [[ ! -f ${s%.svg}.d2 ]]; then
    echo "orphan SVG without a .d2 source: $s" >&2
    orphans=1
  fi
done
exit "$orphans"
