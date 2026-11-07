# 0037 — Code format and dead-code audit

**Status.** Accepted. Merges old 0047, 0054 (texts merged in PR 13 of the reset). Until then each part below is the old record as it was, with its numbers and links moved to the new ones.

<a id="part-0047"></a>

## Part: was ADR 0047 — Code format: Google style for Java and Kotlin, Prettier for the frontend, checked in CI

**Status.** Accepted. **Builds on:** [0033](../operations/0033-ci-cd-and-supply-chain.md)

### Context
The backend is written in Java and Kotlin, the frontend in TypeScript and React. Without a formatter, every editor and every coding assistant formats the
code its own way, and a diff mixes real changes with moved spaces and wrapped lines. A review then takes longer, and a
whole-file reformat by an IDE can hide a change. The rules must be the same on a laptop and in CI, and need no
setup beyond Gradle or npm.

### Decision
1. **Style: Google.** google-java-format for Java, ktfmt with `googleStyle()` for Kotlin and the Gradle build file.
   Two-space indent, 100 columns. Neither formatter has options to argue about.
2. **Tool: Spotless** (Gradle plugin, Apache-2.0) runs both formatters from `backend/build-logic/src/main/kotlin/helper2.format.gradle.kts`.
   `./gradlew spotlessApply` rewrites the files, `./gradlew spotlessCheck` fails on any file that is not formatted.
   It formats `src/**/*.java`, `src/**/*.kt` and `*.gradle.kts`. Generated OpenAPI code lives in `build/` and is not
   formatted.
3. **Versions** are pinned in the build file: the latest release that works with the others, chosen with Silvio.
4. **CI:** the backend job in `ci.yml` runs `spotlessCheck` before the tests. An unformatted file fails the pull request.
5. **Frontend: Prettier**, from `frontend/package.json`. Config in `frontend/.prettierrc.json`: 100 columns like the
   backend, single quotes and trailing commas. `npm run format` rewrites the files, `npm run format:check` fails on
   any file that is not formatted. `frontend/.prettierignore` leaves out the generated `src/api/schema.ts`, `dist`,
   the lockfile and Markdown. `eslint-config-prettier` comes last in `eslint.config.js` and turns off the ESLint
   rules that clash with Prettier; the TypeScript, accessibility and React hooks rules stay.
6. **CI for the frontend:** the frontend job in `ci.yml` runs `format:check` before lint.
7. **Editors:** `.editorconfig` and `.idea/codeStyles/Project.xml` set the same indent and margin, so the IDE agrees
   with the formatters. Spotless and Prettier are the source of truth when they differ.

No runtime path: this decision runs in the build and in CI only, so it has no sequence diagram.

### Consequences
- A diff shows only real changes: the format of every Java, Kotlin, TypeScript and CSS file is fixed.
- No style discussion in review: the formatter decides.
- `spotlessCheck` and `format:check` add a few seconds to their CI jobs.
- A version bump of a formatter can reformat many files: it comes as its own pull request.

### Alternatives considered
- ktlint for Kotlin — a linter with formatting; its style is configurable, which brings back the options Google style
  avoids, and it does not cover Java.
- Formatter plugins per language (one Gradle plugin for google-java-format, one for ktfmt) — two plugins to keep up to
  date instead of one; Spotless runs both with the same commands.
- IDE formatting only — differs between IntelliJ versions and settings, and CI cannot check it.
- AOSP style (google-java-format `--aosp`, 4-space indent) — same rules, smaller first diff, but not the Google style
  the project chose.
- Biome for the frontend — formatter and linter in one, faster, but it would replace ESLint, and its accessibility
  and React hooks rules cover only part of `eslint-plugin-jsx-a11y` and `eslint-plugin-react-hooks`.
- dprint for the frontend — fast, but a smaller ecosystem and less editor support than Prettier.

### Code
- `backend/build-logic/src/main/kotlin/helper2.format.gradle.kts` — the Spotless plugin and the `spotless {}` block.
- `frontend/package.json` (`format`, `format:check`), `frontend/.prettierrc.json`, `frontend/.prettierignore`,
  `frontend/eslint.config.js` — Prettier and its place next to ESLint.
- `.github/workflows/ci.yml` (`backend`, `frontend`) — the `spotlessCheck` and `format:check` steps.
- `.editorconfig`, `.idea/codeStyles/Project.xml` — the editor settings.

### Diagrams
[flow](../../diagrams/flow-0037-code-format.svg)

### Roadmap
None.

<a id="part-0054"></a>

## Part: was ADR 0054 — Dead code and dependency audit: knip in strict mode and npm audit in the frontend CI job

**Status.** Accepted. **Builds on:** [0033](../operations/0033-ci-cd-and-supply-chain.md), [the part that was ADR 0047](#part-0047)

### Context
The frontend changes fast: the v4 redesign replaced most pages and components, and every rewrite can leave a
component, an export or a type that nothing uses any more. TypeScript (`noUnusedLocals`) and ESLint only see one
file at a time: an exported name that no other file imports, a file nothing imports, or a package in
`package.json` that no code needs passes both. Dead code costs review time and makes the code look bigger than it is.

The images are scanned by Grype on main ([0033](../operations/0033-ci-cd-and-supply-chain.md)), but a pull request builds no
image, so a new npm package with a known vulnerability is found only after the merge.

### Decision
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

### Consequences
- A pull request that leaves an unused file, export, type or package fails. The fix is to delete it, or to use it.
- A new or updated production package with a High or Critical advisory fails the pull request, before the image
  is built.
- The first run found 20 names exported but used only in their own file (they lost the `export`), and dead code:
  `FreshnessBadge`, `RINGS_ONLY_MAX` and four type aliases. It found no unused file or package.
- knip does not read locale files or CSS: a text key or a class that only dead code used stays until someone
  removes it by hand.
- `npm run knip` adds a few seconds to the job (both runs take about 2 s on a laptop); the audits need the npm registry.

### Alternatives considered
- ts-prune — finds unused exports only, no files or dependencies; its last release is from 2022.
- eslint-plugin-unused-imports with depcheck — two tools for half the job: unused imports per file (TypeScript
  already reports them) and unused packages. No unused exports or files, and depcheck has had no release since 2023.
- knip in default mode only — passes when app code imports a devDependency.
- Fail on any `npm audit` advisory — the build and test tools often have Moderate advisories with no fix in
  range (today: minimatch under typescript-eslint), and they never run in a browser.

### Code
- `frontend/knip.json` — entries, project files, the issue types.
- `frontend/package.json` (`knip`, `preknip`) — the two runs, after the API types are generated.
- `.github/workflows/ci.yml` (`frontend`) — the knip step and the two `npm audit` steps.

### Diagrams
[flow](../../diagrams/flow-0037-dead-code-and-dependency-audit.svg)

### Roadmap
None.
