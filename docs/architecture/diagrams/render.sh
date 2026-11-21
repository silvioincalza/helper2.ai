#!/usr/bin/env bash
set -euo pipefail
readonly WANT=v0.9.0
cd "$(dirname "$0")"

png=0
[[ ${1:-} == --png ]] && png=1

is_specification_twin() {
  [[ $1 == use-cases-* || $1 == entity-model-* ]]
}

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
  if ((png)) && is_specification_twin "$f"; then
    d2 --scale 1 "$f" "${f%.d2}.png"
  fi
done

orphans=0
for s in *.svg; do
  [[ $s == hexagonal-architecture.svg || $s == event-storming.svg ]] && continue
  if [[ ! -f ${s%.svg}.d2 ]]; then
    echo "orphan SVG without a .d2 source: $s" >&2
    orphans=1
  fi
done
for f in *.d2; do
  if is_specification_twin "$f" && [[ ! -f ${f%.d2}.png ]]; then
    echo "specification twin without its PNG: $f (run render.sh --png)" >&2
    orphans=1
  fi
done
for p in *.png; do
  if [[ ! -f ${p%.png}.d2 ]]; then
    echo "orphan PNG without a .d2 source: $p" >&2
    orphans=1
  fi
done
exit "$orphans"
