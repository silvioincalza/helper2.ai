# 0047 — Code format and dead-code audit

**Depends on:** [0044](0044-ci-cd-and-supply-chain.md).

## Context

The backend is written in Java and Kotlin, the frontend in TypeScript and React. Without a formatter, every editor and
every coding assistant formats the code its own way, and a diff mixes real changes with moved spaces and wrapped
lines. A review then takes longer, and a whole-file reformat by an IDE can hide a change. The rules must be the same
on a laptop and in CI, and need no setup beyond Gradle or npm.

The frontend also changes fast: a redesign rewrites most pages and components, and every rewrite can leave a
component, an export or a type that nothing uses any more. TypeScript (`noUnusedLocals`) and ESLint only see one file
at a time: an exported name that no other file imports, a file nothing imports, or a package in `package.json` that
no code needs passes both. Dead code costs review time and makes the code look bigger than it is.

The images are scanned by Grype on main ([0044](0044-ci-cd-and-supply-chain.md)), but a pull request
builds no image, so a new npm package with a known vulnerability would be found only after the merge.

## Decision

### Code format
1. **Style: Google.** google-java-format for Java, ktfmt with `googleStyle()` for Kotlin and the Gradle build files.
   Two-space indent, 100 columns. Neither formatter has options to argue about.
2. **Tool: Spotless** (Gradle plugin, Apache-2.0) runs both formatters from the convention plugin
   `backend/build-logic/src/main/kotlin/helper2.format.gradle.kts`. It formats `src/**/*.java`, `src/**/*.kt` and
   `*.gradle.kts` of every project; `backend/build-logic/build.gradle.kts` applies the same ktfmt rule to the
   convention plugins themselves. `./gradlew spotlessApply` rewrites the files, `./gradlew spotlessCheck` fails on any
   file that is not formatted. Generated OpenAPI code lives in `build/` and is not formatted.
3. **Versions** are pinned in the build files: Spotless 8.11.0, google-java-format 2.0.0, ktfmt 0.64. Each is the
   latest release that works with the others, chosen with the owner.
4. **CI for the backend:** the `backend-check` job in `ci.yml` runs `spotlessCheck` before the tests. An unformatted file
   fails the pull request.
5. **Frontend: Prettier**, from `frontend/package.json`. Config in `frontend/.prettierrc.json`: 100 columns like the
   backend, single quotes and trailing commas. `npm run format` rewrites the files, `npm run format:check` fails on
   any file that is not formatted. `frontend/.prettierignore` leaves out the generated `src/api/schema.ts` and
   `src/api/admin-schema.ts`, `dist`, `dist-admin`, `node_modules`, the lockfile and Markdown: prose keeps its own
   line breaks. Prettier runs inside `frontend/` only, so no Markdown of the repository is formatted.
   `eslint-config-prettier` comes last in `eslint.config.js` and turns off the ESLint rules that clash with Prettier;
   the TypeScript, accessibility and React hooks rules stay.
6. **CI for the frontend:** the `frontend-check` job in `ci.yml` runs `format:check` before lint.
7. **Editors:** `.editorconfig` and `.idea/codeStyles/Project.xml` set the same indent and margin, so the IDE agrees
   with the formatters. Spotless and Prettier are the source of truth when they differ.

### Dead code and dependency audit
8. **Tool: knip** (ISC licence, 6.44.1). It reads the whole project from its entry points and reports unused files,
   exports, types and dependencies in one run. Its plugins read the Vite, Vitest, ESLint and Prettier configs and the
   npm scripts, so they need no entry of their own.
9. **Config: `frontend/knip.json`.** Entries: `index.html` and the configs through the plugins, plus `scripts/*.mjs`
   (the bundle check). Project: `src/**/*.{ts,tsx}`, the scripts and the root configs. `src/test/` is a test helper,
   not production code. No ignore list: the generated `src/api/schema.ts` and `src/api/admin-schema.ts` are
   git-ignored, and knip skips git-ignored files. `preknip` generates them first (`npm run gen:api`), so the imports
   resolve.
10. **Strict.** Every issue type fails the run: unused files, dependencies, devDependencies, optional peer
    dependencies, unlisted dependencies and binaries, unresolved imports, unused exports and types (also through a
    namespace), unused enum and namespace members, duplicate exports and import cycles. knip 6 has no check for class
    members any more; the frontend has no classes.
11. **Two runs.** `npm run knip` first runs the whole project, tests included. Then it runs in production strict mode
    (`--production --strict`) without the export checks: files, dependencies, unlisted and unresolved imports,
    binaries and cycles. The code the browser gets may use only the packages in `dependencies`, not a devDependency.
    An export used only by a test is fine: tests import the pure functions they check.
12. **npm audit.** In the `frontend-check` job, after typecheck: `npm audit --omit=dev --audit-level=high` fails on a
    High or Critical advisory in a package the browser gets. A second, full `npm audit` lists the build and test tools
    too, as a report only (`continue-on-error`): they never reach a user.
13. **CI order in the frontend jobs:** `frontend-check` runs `format:check`, lint, `knip`, typecheck, then the two
    audits; `frontend-build` runs the build and the bundle check, and `frontend-test` the tests, side by side with it.

No runtime path: these decisions run in the build and in CI only, so there is no sequence diagram.

### Code

- `backend/build-logic/src/main/kotlin/helper2.format.gradle.kts` — the Spotless plugin and the `spotless {}` block;
  `backend/build-logic/build.gradle.kts` — the plugin's version and the format of the convention plugins.
- `frontend/package.json` (`format`, `format:check`, `knip`, `preknip`), `frontend/.prettierrc.json`,
  `frontend/.prettierignore`, `frontend/eslint.config.js` — Prettier, its place next to ESLint, and the two knip runs.
- `frontend/knip.json` — entries, project files, the issue types.
- `.github/workflows/ci.yml` (`backend-check`, `frontend-check`) — the `spotlessCheck`, `format:check` and knip steps and the two
  `npm audit` steps.
- `.editorconfig`, `.idea/codeStyles/Project.xml` — the editor settings.

## Alternatives considered

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
- ts-prune — finds unused exports only, no files or dependencies; its last release is from 2022.
- eslint-plugin-unused-imports with depcheck — two tools for half the job: unused imports per file (TypeScript
  already reports them) and unused packages. No unused exports or files, and depcheck has had no release since 2023.
- knip in default mode only — passes when app code imports a devDependency.
- Fail on any `npm audit` advisory — the build and test tools often have Moderate advisories with no fix in range,
  and they never run in a browser.

## Consequences

- A diff shows only real changes: the format of every Java, Kotlin, TypeScript and CSS file is fixed.
- No style discussion in review: the formatter decides.
- `spotlessCheck` and `format:check` add a few seconds to their CI jobs.
- A version bump of a formatter can reformat many files: it comes as its own pull request.
- A pull request that leaves an unused file, export, type or package fails. The fix is to delete it, or to use it.
- A new or updated production package with a High or Critical advisory fails the pull request, before the image is
  built.
- The first knip run found 20 names exported but used only in their own file (they lost the `export`), and dead code:
  `FreshnessBadge`, `RINGS_ONLY_MAX` and four type aliases. It found no unused file or package.
- knip does not read locale files or CSS. Two frontend tests cover that gap: a class no code writes
  (`styles/classesUsed.test.ts`) and a translation key no code reads (`i18n/keysUsed.test.ts`)
  ([0040](../frontend/0040-design-system.md)).
- `npm run knip` adds a few seconds to the job (both runs take about 2 s on a laptop); the audits need the npm
  registry.

### Known limits

- knip does not read locale files or CSS; two tests do (`styles/classesUsed.test.ts`, `i18n/keysUsed.test.ts`). They see a class or a key the code writes whole or under a template prefix (`` `h2-dot--${tone}` ``); one built another way (strings joined with `+`) counts as unused, and a key or class that only dead code uses still counts as used. Moderate `npm audit` advisories in build and test tools are only reported.

## Diagrams

Code format: [flow](../../diagrams/flow-0047-code-format.svg) · dead code and dependency audit:
[flow](../../diagrams/flow-0047-dead-code-and-dependency-audit.svg). Build and CI only, no runtime path, so no
sequence diagram.

## Backlog

- Features: [F10.5](https://github.com/silvioincalza/helper2.ai/issues/111) Code format and dead-code audit.
- Open issues that change this decision: none.
