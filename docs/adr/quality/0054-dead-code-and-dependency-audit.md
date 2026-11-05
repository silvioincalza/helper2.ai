# 0054 — Dead code and dependency audit: knip in strict mode and npm audit in the frontend CI job

**Status.** Accepted. **Builds on:** [0028](../operations/0028-ci-cd.md), [0046](../operations/0046-supply-chain.md), [0047](0047-code-format.md)

## Context
The frontend changes fast: the v4 redesign replaced most pages and components, and every rewrite can leave a
component, an export or a type that nothing uses any more. TypeScript (`noUnusedLocals`) and ESLint only see one
file at a time: an exported name that no other file imports, a file nothing imports, or a package in
`package.json` that no code needs passes both. Dead code costs review time and makes the code look bigger than it is.

The images are scanned by Grype on main ([0046](../operations/0046-supply-chain.md)), but a pull request builds no
image, so a new npm package with a known vulnerability is found only after the merge.

## Decision
1. **Tool: knip** (ISC licence, 6.44.1, the latest release). It reads the whole project from its entry points and
   reports unused files, exports, types and dependencies in one run. Its plugins read the Vite, Vitest, ESLint and
   Prettier configs and the npm scripts, so they need no entry of their own.
2. **Config: `frontend/knip.json`.** Entries: `index.html` and the configs through the plugins, plus
   `scripts/*.mjs` (the bundle check). Project: `src/**/*.{ts,tsx}`, the scripts and the root configs.
   `src/test/` is a test helper, not production code. No ignore list: the generated `src/api/schema.ts` is
   git-ignored, and knip skips git-ignored files.
3. **Strict.** Every issue type fails the run: unused files, dependencies, devDependencies, optional peer
   dependencies, unlisted dependencies and binaries, unresolved imports, unused exports and types (also through a
   namespace), unused enum and namespace members, duplicate exports and import cycles. knip 6 has no check for class
   members any more; the frontend has no classes.
4. **Two runs.** `npm run knip` first runs the whole project, tests included. Then it runs in production strict
   mode (`--production --strict`) for files and dependencies only: the code the browser gets may use only the
   packages in `dependencies`, not a devDependency. An export used only by a test is fine: tests import the pure
   functions they check.
5. **npm audit.** In the frontend CI job, after the build: `npm audit --omit=dev --audit-level=high` fails on a
   High or Critical advisory in a package the browser gets. A second, full `npm audit` lists the build and test
   tools too, as a report only (`continue-on-error`): they never reach a user.
6. **CI:** the frontend job in `ci.yml` runs `npm run knip` after lint, and the two audits after the bundle check.

No runtime path: this decision runs in the build and in CI only, so it has no sequence diagram.

## Consequences
- A pull request that leaves an unused file, export, type or package fails. The fix is to delete it, or to use it.
- A new or updated production package with a High or Critical advisory fails the pull request, before the image
  is built.
- The first run found 20 names exported but used only in their own file (they lost the `export`), and dead code:
  `FreshnessBadge`, `RINGS_ONLY_MAX` and four type aliases. It found no unused file or package.
- knip does not read locale files or CSS: a text key or a class that only dead code used stays until someone
  removes it by hand.
- `npm run knip` adds a few seconds to the job (both runs take about 2 s on a laptop); the audits need the npm registry.

## Alternatives considered
- ts-prune — finds unused exports only, no files or dependencies; its last release is from 2022.
- eslint-plugin-unused-imports with depcheck — two tools for half the job: unused imports per file (TypeScript
  already reports them) and unused packages. No unused exports or files, and depcheck has had no release since 2023.
- knip in default mode only — passes when app code imports a devDependency.
- Fail on any `npm audit` advisory — the build and test tools often have Moderate advisories with no fix in
  range (today: minimatch under typescript-eslint), and they never run in a browser.

## Code
- `frontend/knip.json` — entries, project files, the issue types.
- `frontend/package.json` (`knip`, `preknip`) — the two runs, after the API types are generated.
- `.github/workflows/ci.yml` (`frontend`) — the knip step and the two `npm audit` steps.

## Diagrams
[flow](../../diagrams/flow-0054-dead-code-and-dependency-audit.svg)

## Roadmap
None.
