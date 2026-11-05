# 0049 — Home examples: a pool in the database, a nightly quality check, a random pick in the browser

**Status.** Accepted. **Builds on:** [0008](0008-federated-search.md), [0009](0009-ranking.md), [0023](../edge/0023-identity-and-admin.md), [0036](../quality/0036-search-evaluation-harness.md), [0040](../frontend/0040-frontend-design.md), [0045](../edge/0045-back-office.md)

## Context
Under the home search box, **Try:** shows example searches. For many visitors they are the first search they make, so
each one is a promise: click it and you get a right answer at once. Four fixed examples did not keep it: on staging
"Summarise long PDFs" and "Run a model on my laptop" had no relevant result in the top 5. Fixed examples also show the
same four needs to everyone, and changing one needs a release.
I want a pool to pick from, examples I can add and change without a release, and a check that hides an example the
moment it stops keeping its promise. The pool is small (tens of examples), changes a few times a month, and is the
same for every visitor.

## Decision
1. **A pool in PostgreSQL, module `examples`.** Table `home_example`: label (2–32 characters), query (3–200), audience
   path (`USE_AI`, `BUILD_WITH_AI`), locale, status (`ACTIVE`, `PAUSED`), 1 to 3 expected results (catalog resource
   ids, no foreign key), origin (`BUILT_IN`, `CURATED`, `AI_DRAFTED` with its model), the last check, and a version.
   Texts are stored normalised (NFC, trimmed, one space) and refused with `<`, `>`, a web address or a control
   character; label and query are unique per locale in any case. The migration seeds the built-in examples of the
   frontend with the results that made them pass on staging.
2. **The quality check.** An example is shown only when an expected result is in the top 3 of its own search: the
   same query and path a click sends, 10 results, without web search (`SearchRequest.web = false`: the metered source
   is not asked, so the check spends no web credits and does not follow web answers that change every day). Top 3 is
   `PASS`, 4 to 10 `WEAK`, beyond `FAIL`; only `PASS` is shown. A degraded search (a source we asked did not answer,
   or the index was down) keeps the last result. A result is stored only if the example's search is still the one
   checked. The check runs on one replica at a time (cluster lock): `PENDING` examples every 5 minutes, every example
   each night (`H2_EXAMPLES_*`). It is the same rule as the eval group `home-example` (ADR 0036), so a ranking change
   that loses an example shows offline before it ships.
3. **Admin writes go back to `PENDING`.** Adding an example, or changing what it searches (query, path, expected
   results), sends it to `PENDING`: hidden until checked. A label or status change keeps the check. A change names
   the version it read; an older version is a 409 `version-conflict`. Every add, change and removal is in the audit
   trail, in the same transaction.
4. **One public endpoint, the pick in the browser.** `GET /api/v1/home/examples` returns the examples that are
   `ACTIVE` and `PASS`, at most 50, the same for everyone (`Cache-Control: public, max-age=300`), with the English
   ones when a locale has none. The page picks two per path at random, people who use AI first. An example searches;
   it does not ask the guide, so it spends no daily AI answer.
5. **The back office.** `/api/v1/admin/home-examples` lists, adds, changes and removes examples (ADMIN, admin host),
   in the admin module like every back-office endpoint (ADR 0045).

## Consequences
- I change the examples without a release, and a bad example hides itself instead of greeting a new visitor.
- What the page shows is what was checked: the same query and path, against the same ranking.
- A new or changed example shows up to 5 minutes later; the page may serve the old pool for 5 more minutes (cache).
- The check is one search per example: about a dozen searches a night, no model call, no web credit.
- An example whose expected result leaves the catalog fails the check; it is hidden, not deleted.

## Alternatives considered
- Keep the pool in the bundle (built-in list only) — no check at runtime, a release for every change. The built-in
  list stays as the page's fallback and in the eval group.
- Pick on the server — one answer per visitor, so no shared cache, for a payload of a few kilobytes.
- Check through the guide — a model call per example per night, and answers that vary run to run. The guide is not
  what a click asks.
- Check only that a search returns something — the review showed the real problem is a full page of wrong results,
  not an empty one.

## Code
- `backend/src/main/kotlin/ai/helper2/examples/` — `HomeExample.kt` (example, input rules), `ExampleCheck.kt` (the
  check, `ExampleSearch`), `ExamplePorts.kt`.
- `backend/src/main/java/ai/helper2/examples/` — `usecase/` (`ListHomeExamples`, `ListAdminHomeExamples`,
  `AddHomeExample`, `ChangeHomeExample`, `RemoveHomeExample`, `CheckHomeExamples`), `config/ExamplesProperties`,
  `internal/` (`JdbcHomeExamples`,
  `SearchExampleSearch`, `CatalogResourceTitles`, `HomeExampleCheckSchedule`), `web/HomeExamplesController.java`.
- `backend/src/main/java/ai/helper2/admin/web/HomeExamplesAdminController.java`.
- `backend/src/main/kotlin/ai/helper2/search/Search.kt` (`SearchRequest.web`), `Model.kt` (`SkipReason.WEB_NOT_ASKED`).
- `backend/src/main/resources/db/migration/V4__home_example.sql`.
- `frontend/src/lib/examples.ts` — the built-in pool and the pick.
- Tests: `examples/ExampleInputTest.kt`, `ExampleCheckTest.kt`, `search/usecase/MeteredSourceTest.kt`,
  `api/HomeExamplesApiIT.java`, `frontend/src/lib/examples.test.ts`.

## Diagrams
[flow](../../diagrams/flow-0049-home-examples.svg) · [sequence](../../diagrams/sequence-0049-home-examples.svg)

## Roadmap
- The page reads the pool from the endpoint, with the built-in list when it does not answer in time.
- The back office Examples page, and the count of hidden examples on the overview.
- AI-drafted examples that wait in the Review queue.
