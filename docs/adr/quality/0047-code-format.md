# 0047 — Code format: Google style for Java and Kotlin, applied by Spotless, checked in CI

**Status.** Accepted. **Builds on:** [0028](../operations/0028-ci-cd.md)

## Context
The backend is written in Java and Kotlin. Without a formatter, every editor and every coding assistant formats the
code its own way, and a diff mixes real changes with moved spaces and wrapped lines. A review then takes longer, and a
whole-file reformat by an IDE can hide a change. The rules must be the same on a laptop and in CI, and need no
setup beyond Gradle.

## Decision
1. **Style: Google.** google-java-format for Java, ktfmt with `googleStyle()` for Kotlin and the Gradle build file.
   Two-space indent, 100 columns. Neither formatter has options to argue about.
2. **Tool: Spotless** (Gradle plugin, Apache-2.0) runs both formatters from `backend/build.gradle.kts`.
   `./gradlew spotlessApply` rewrites the files, `./gradlew spotlessCheck` fails on any file that is not formatted.
   It formats `src/**/*.java`, `src/**/*.kt` and `*.gradle.kts`. Generated OpenAPI code lives in `build/` and is not
   formatted.
3. **Versions** are pinned in the build file: the latest release that works with the others, chosen with Silvio.
4. **CI:** the backend job in `ci.yml` runs `spotlessCheck` before the tests. An unformatted file fails the pull request.
5. **Editors:** `.editorconfig` and `.idea/codeStyles/Project.xml` set the same indent and margin, so the IDE agrees
   with Spotless. Spotless is the source of truth when they differ.

No runtime path: this decision runs in the build and in CI only, so it has no sequence diagram.

## Consequences
- A diff shows only real changes: the format of every Java and Kotlin file is fixed.
- No style discussion in review: the formatter decides.
- `spotlessCheck` adds a few seconds to the backend CI job.
- A version bump of a formatter can reformat many files: it comes as its own pull request.

## Alternatives considered
- ktlint for Kotlin — a linter with formatting; its style is configurable, which brings back the options Google style
  avoids, and it does not cover Java.
- Formatter plugins per language (one Gradle plugin for google-java-format, one for ktfmt) — two plugins to keep up to
  date instead of one; Spotless runs both with the same commands.
- IDE formatting only — differs between IntelliJ versions and settings, and CI cannot check it.
- AOSP style (google-java-format `--aosp`, 4-space indent) — same rules, smaller first diff, but not the Google style
  the project chose.

## Code
- `backend/build.gradle.kts` — the Spotless plugin and the `spotless {}` block.
- `.github/workflows/ci.yml` (`backend`) — the `spotlessCheck` step.
- `.editorconfig`, `.idea/codeStyles/Project.xml` — the editor settings.

## Diagrams
[flow](../../diagrams/flow-0047-code-format.svg)

## Roadmap
None.
