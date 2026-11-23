#!/usr/bin/env bash
set -euo pipefail

root="$(git rev-parse --show-toplevel)"
what="${1:-all}"

backend() {
  (cd "$root/backend" && ./gradlew spotlessCheck test)
}

frontend() {
  # CI runs with no NODE_ENV. A shell with NODE_ENV=production gives a production React build in the
  # tests, and makes npm ci skip the dev tools (Prettier, ESLint, Vitest).
  unset NODE_ENV
  if [[ ! -x "$root/frontend/node_modules/.bin/prettier" ]]; then
    echo "verify: the frontend dev tools are missing: run 'npm ci --include=dev' in frontend/" >&2
    return 1
  fi
  (cd "$root/frontend" && npm run format:check && npm run lint && npm run knip && npm run typecheck && npm test -- --run \
    && npx vite build && npm run check:bundle && npx vite build --config vite.admin.config.ts)
}

docs() {
  (cd "$root" && python3 scripts/adr-check.py && python3 .claude/hooks/guard-bash.py --self-test && scripts/spec-lint.sh)
}

case "$what" in
  backend) backend ;;
  frontend) frontend ;;
  docs) docs ;;
  all)
    docs
    frontend
    backend
    ;;
  *) echo "usage: scripts/verify.sh [backend|frontend|docs|all]" >&2; exit 64 ;;
esac

echo "verify: $what passed"
