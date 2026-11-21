#!/usr/bin/env bash
set -euo pipefail

root="$(git rev-parse --show-toplevel)"
plugins="$HOME/.claude/plugins/marketplaces/ai-unified-process-marketplace"
if [[ $# -gt 0 && $1 != -* ]]; then
  plugins=$1
  shift
fi
lint="$plugins/aiup-core/skills/spec-review/scripts/spec_lint.py"

if [[ ! -f $lint ]]; then
  echo "spec-lint: $lint not found." >&2
  echo "usage: scripts/spec-lint.sh [folder of the AI Unified Process marketplace] [flags of the linter]" >&2
  exit 66
fi

cd "$root"
python3 "$lint" --docs docs --strict "$@"
