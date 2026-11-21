---
name: frontend-conventions
description: Use before writing, changing or reviewing any file under frontend/ (React, TypeScript, CSS, tests, ESLint config). Holds the frontend commands, the feature and import rules of ADR 0050, the design-token rule and the format rule.
---

# Frontend conventions

`frontend/` — React 19, Vite, TypeScript, Vitest.

## Commands

```
cd frontend && npm test -- --run && npm run lint && npm run typecheck
cd frontend && npm run format        # Prettier (CI runs format:check)
cd frontend && npm run knip          # dead code and unused dependencies, strict (CI runs it)
```

Run the relevant tests before calling a change done. `scripts/verify.sh frontend` runs what CI runs.

## Rules

- Frontend (ADR 0050): a feature imports no file of another feature. A piece two features need goes to `ui/`, `lib/`
  or `hooks/`; a composition is named in `COMPOSES` in `frontend/eslint.config.js`. An import is `@/` for a file of
  another folder and `./name` for a file of the same folder, never `../`. `npm run lint` checks it.
- The design system is the rule (`frontend/design/README.md`, ADR 0040): use its tokens and components, and propose a
  change to it before adding a one-off style.
- Code format (ADR 0047): Prettier for the frontend. Run `npm run format` before a commit; CI fails on unformatted
  code.
- BDD for user-visible behaviour: Vitest and Testing Library tests next to each page and component.
- No dead code: `npm run knip` is strict.
- A user-visible change also follows the skill `ux-rules`.
