# 0050 — Frontend: feature boundaries and import style

**Status:** Accepted

**Date:** 2026-11-18

**Depends on:** [0039](ADR-0039-frontend-one-box-state-in-the-url.md), [0044](ADR-0044-ci-cd-and-supply-chain.md), [0047](ADR-0047-code-format-and-dead-code-audit.md).

**Requirements:** C-014

The backend has the same kind of rule for its modules in [0002](ADR-0002-modules-modular-monolith-hexagonal-where-the-rules-live.md).

## Context

`frontend/src/features/` has one folder per feature ([0039](ADR-0039-frontend-one-box-state-in-the-url.md) §9): the home
page, the search, the guide, a resource page, the saved list. A feature is easy to change when its files import shared code and each other, and nothing else. Then one folder
is all there is to read.

Nothing in TypeScript, Vite or knip keeps it that way. Any file can import any other file. A component that two pages
need stays in the feature that had it first, and the second feature imports it from there. After some time one feature
works as a shared module, two features import each other, and a change in one breaks a page of another. knip reports
an import cycle between files ([0047](ADR-0047-code-format-and-dead-code-audit.md)), but not a feature
that reads another feature.

The back office (`src/admin/`) is a second app with its own bundle
([0039](ADR-0039-frontend-one-box-state-in-the-url.md) §8). A back office file that imports a feature pulls that feature's
code into the admin bundle. A public file that imports a back office file does the opposite.

Imports are also written in two ways: `@/features/home/Home` and `../home/Home`. A relative path to a parent folder
hides which folder it reaches, and it breaks when the file moves.

## Decision

1. **A feature imports no file of another feature.** A file in `features/<name>/` imports shared code and the files
   of `features/<name>/`. The compositions of §4 are the only exceptions, and each has a name.
2. **What each folder of `src/` may import.** A folder that is not in its row is refused.

   | Folder | Holds | May import |
   |---|---|---|
   | `api/`, `lib/`, `i18n/`, `styles/` | the API client and its types, functions without a component, the copy, the tokens | `api/`, `lib/`, `i18n/`, `styles/` |
   | `context/` | state shared by the whole app (the user, the bookmarks, the monetisation switches) | the row above, `context/` |
   | `hooks/` | hooks that no feature owns (`useApi`, `useTitle`, `useMediaQuery`) | the rows above, `hooks/` |
   | `ui/` | components and CSS that two features or both apps show | the rows above, `ui/` |
   | `features/<name>/` | one feature | shared code (every row above), its own files, the features it composes (§4) |
   | `shell/`, `routes.tsx`, `main.tsx`, `AppProviders.tsx` | the public app: its layout, its routes, the page of `/` | shared code, every feature, `shell/` |
   | `admin/` | the back office app | shared code, its own files, `shell/RouteError` |
   | `test/`, `src/*.test.tsx` | the test harness and the tests that cross every folder | every folder |

   "Shared code" is `api/`, `lib/`, `i18n/`, `styles/`, `context/`, `hooks/` and `ui/`. Shared code never imports a
   feature, `shell/` or `admin/`. A new top folder gets the first row until it has its own.
3. **A piece that two features need goes down to shared code.** It does not stay in the feature that had it first.
   - A component goes to `ui/`: `TermCommand`, `ListState`, `BookmarkButton`, `SignIn`, `ResultDate`,
     `DiscoveryCategories`, `Robots`, and the text page style `text.css`.
   - A function, a constant or router state without a component goes to `lib/`: `fromResults.ts` (where the user
     came from), `rowAnchor` (`searchState.ts`), `toneOf` (`sourceHealth.ts`), `radarRotation.ts`, `repoUrl.ts`.
   - A hook that one feature owns stays in that feature: `useGuideStream` is in `features/guide/`. `hooks/` keeps the
     hooks that no feature owns.
4. **A composition is named, and it goes one way.** Some pages are made of other features: the results page shows
   the guide's picks. Such a feature may import the features it composes. The list is `COMPOSES` in
   `frontend/eslint.config.js`, by feature, and it has no cycle: no feature composes a feature that composes it,
   directly or through another.

   | Feature | Composes | Why |
   |---|---|---|
   | `home` | `feeds` | the home page shows the first rows of the news and the events lists (`HomeLists`, `HomeTabs`, `TermList`) |
   | `home` | `search` | the home page shows the one box (`OneBox`) |
   | `search` | `guide` | the results page runs the guide stream and shows its picks, and the box shows the answers left (`useGuideStream`, `GuidePicks`, `WebSearchesLeft`, `GuideAnswersLeft`) |
   | `search` | `monetisation` | the results page shows the support line (`SupportWhileSearching`) |
   | `resource` | `setup` | the resource page shows "Set it up" (`SetupGuidePanel`, `canGuide`) |
   | `resource` | `monetisation` | the resource page shows the ad slot (`AdSenseSlot`) |

   A page that composes two features which compose each other belongs to `shell/`: `HomeOrResultsPage` shows the home
   page or the results, and `Layout` shows the box, the sign-in link and the consent banner.
   `features/feeds/` holds the news and events feeds, their cells and their two pages (`feeds.ts`, `feedCells.tsx`,
   `NewsPage`, `EventsPage`).
   A new composition is one more entry in `COMPOSES` and one more row here, with its reason.
5. **A feature has no public entry file.** There is no `index.ts`. An import names the file it needs. The pages are
   loaded on demand, one chunk per page ([0039](ADR-0039-frontend-one-box-state-in-the-url.md) §8): an entry file that
   exports the whole feature would put the page and its small parts in one module graph for every importer.
6. **The two apps stay apart.** `admin/` imports no feature and no file of `shell/` but `RouteError`. No folder of
   the public app imports `admin/`. What both apps show is in `ui/`.
7. **Tests follow the rule of their folder.** A test in `features/saved/` imports what `features/saved/` may import.
   A test that reads across folders is at the root of `src/` (`a11y.test.tsx`, `loading.test.tsx`,
   `limits.contract.test.ts`, `adminBundle.test.ts`). The harness in `src/test/` renders both apps, so it imports
   both.
8. **One import style.** `@/` for a file of another folder, `./name` for a file of the same folder. No `../`, no
   `./sub/name`, and no `@/` for a file of the own folder.
9. **ESLint checks all of it, with rules it already has.** `no-restricted-imports` checks `import` and
   `export ... from`. `no-restricted-syntax` checks the same patterns in `import()` and in `vi.mock()` and the other
   `vi` calls. `eslint.config.js` reads the folders of `src/` and writes one block per folder: the style patterns
   of §8, then the patterns of the folder's row in §2. Both rules are at `error`, and `frontend/src` has no
   `eslint-disable` for them. `npm run lint` runs in the frontend CI job
   ([0044](ADR-0044-ci-cd-and-supply-chain.md)). The message of a refused import names the rule and this
   record.

## Alternatives considered

The rule:

| Option | Simplicity | Coupling | Testability | Load for one person |
|---|---|---|---|---|
| No cross import, named one-way compositions (chosen) | one table of six rows | a feature reads only what its row names | a feature test needs its own folder and shared code | low: the lint message says what to do |
| No cross import at all: a page gets the other feature's component as a prop or a slot | the rule has no exception | lowest | each feature renders alone | high: `Results` and `Resource` pass components and their data through several levels, and every move is a rewrite, not a move |
| A public entry file per feature (`index.ts`), other features import only that | one more file per feature to keep right | the same imports, through a narrower door | no change | medium: knip and the page chunks need care, and two features can still import each other |
| No rule, knip's cycle check only | nothing to learn | grows with every shared piece | a change in one feature breaks another's tests | low now, high later |

The tool (npm registry, read 2026-11-18):

| Candidate | Fit | Maintenance | Licence | Lock-in |
|---|---|---|---|---|
| ESLint `no-restricted-imports` and `no-restricted-syntax` (chosen) | reads the import text; enough because every cross-folder import starts with `@/` (§8) | part of ESLint, already installed | MIT | none: about 100 lines of config |
| `eslint-plugin-boundaries` 7.4.4 | made for this: element types and allowed directions, resolves the import to a file | active: registry entry changed 2026-11 | MIT | its own element and rule vocabulary, and a resolver to configure |
| `eslint-plugin-import` 2.32.0 (`no-restricted-paths`) | zones by path, resolves the import | active: registry entry changed 2026-11; its peer range ends at ESLint 9 | MIT | brings the whole plugin and a TypeScript resolver for one rule |
| `dependency-cruiser` 18.8.0 | rules on the resolved graph, cycles included | active: registry entry changed 2026-11 | MIT | a second tool and a second CI step next to ESLint and knip |

A new package is not needed: the built-in rules do the job, so no version to keep up to date and nothing new for
`npm audit` to read.

## Consequences

- To change a feature, read its folder and the shared code it imports. The six compositions say where one more
  folder matters.
- A new import from one feature into a file of another fails `npm run lint`, on a laptop and in CI. The message says
  to move the piece to `ui/`, `lib/` or `hooks/`, or to name the composition.
- `ui/` is bigger and may read `context/`: `BookmarkButton` reads the bookmarks, `SignIn` reads the user.
- `home` is a feature like the others, not a shared module. The back office header takes `TermCommand` from `ui/`, so
  the admin bundle holds no code of the home lists.
- A file that moves to another folder needs no change to its `@/` imports; only its `./` imports change.
- The pages, the routes and the entry points of both apps are the same. The bundler names a shared chunk after one of
  its modules, so some chunk names differ.
- A new feature folder is checked without a config change: `eslint.config.js` reads the folders.

### Known limits

- The rules read the import text, not the file it resolves to. §8 makes the text and the file agree: a path the
  boundary patterns cannot read (`../`, `./sub/`) fails as a style error first.
- A composition is allowed per feature, not per file: `home` may import any file of `search`, not only `OneBox`.
- Every folder may import `test/`: the rule does not tell a test file from an app file.
- An `import()` or a `vi.mock()` whose path is not a plain string is not read. `@import` inside a CSS file is not
  read either.
- The order inside shared code is checked by folder: `lib/` cannot import `ui/`, but two files of `lib/` and `api/`
  may import each other.

## Diagrams

[flow](../diagrams/flow-0050-frontend-feature-boundaries.svg). Lint and CI only, no runtime path, so no sequence
diagram.

## Backlog

- Features: [F1.11](https://github.com/silvioincalza/helper2.ai/issues/30)
  ([S1.11.20](https://github.com/silvioincalza/helper2.ai/issues/722): features do not import each other's files).
- Open issues that change this decision: none.
