#!/usr/bin/env bash
set -euo pipefail

root="$(git rev-parse --show-toplevel)"
plugins="${1:-$HOME/.claude/plugins/marketplaces/ai-unified-process-marketplace}"
lint="$plugins/aiup-core/skills/spec-review/scripts/spec_lint.py"

if [[ ! -f $lint ]]; then
  echo "spec-lint: $lint not found. Give the folder of the AI Unified Process marketplace as the first argument." >&2
  exit 66
fi

cd "$root"
python3 "$lint" --docs docs --strict "${@:2}"
