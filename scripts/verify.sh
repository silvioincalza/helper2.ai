#!/usr/bin/env bash
# One command that says if a change is ready: it runs the checks CI runs and exits non-zero on the first failure.
# Usage: scripts/verify.sh [backend|frontend|docs|all]   (default: all)
set -euo pipefail

root="$(git rev-parse --show-toplevel)"
what="${1:-all}"

backend() {
  (cd "$root/backend" && ./gradlew spotlessCheck test)
}

frontend() {
  (cd "$root/frontend" && npm run format:check && npm run lint && npm run typecheck && npm run knip && npm test -- --run)
}

docs() {
  (cd "$root" && python3 scripts/adr-check.py && python3 .claude/hooks/guard-bash.py --self-test)
}

case "$what" in
  backend) backend ;;
  frontend) frontend ;;
  docs) docs ;;
  all) docs && frontend && backend ;;
  *) echo "usage: scripts/verify.sh [backend|frontend|docs|all]" >&2; exit 64 ;;
esac

echo "verify: $what passed"
