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
   the version it read; an older version is a 409 `version-conflict`. A new expected result must be in the catalog; one
   already stored may have left it, so a pause or a new label still goes through. The unique indexes decide a
   duplicate, also between two changes at the same moment: the update runs in a savepoint, so the 409 `duplicate` is
   still audited. Every add, change and removal is in the audit trail, in the same transaction.
4. **One public endpoint, the pick in the browser.** `GET /api/v1/home/examples` returns the examples that are
   `ACTIVE` and `PASS`, at most 50, the same for everyone (`Cache-Control: public, max-age=300`), with the English
   ones when a locale has none. The page picks two per path at random, people who use AI first. An example searches;
   it does not ask the guide, so it spends no daily AI answer.
5. **The back office.** `/api/v1/admin/home-examples` lists, adds, changes and removes examples (ADMIN, admin host),
   in the admin module like every back-office endpoint (ADR 0045). The page `/admin/examples` shows each check in
   words, finds expected results with the visitors' own search, and reacts to the problem codes (`duplicate`,
   `version-conflict`, `invalid-request` with the broken rule).

6. **AI drafts, a person decides.** **Draft examples with AI** makes one model call (the fast Nemotron model, the
   drafts' own daily budget `H2_EXAMPLES_DRAFT_CALLS`, 0 = off, inside the `llm-chat` circuit). The model gets public
   facts about well-known catalog resources that no example expects yet (`H2_EXAMPLES_DRAFT_SOURCES`, curated first),
   each fenced as untrusted text, and answers JSON. Code keeps a draft only when it names a resource it was given
   (that resource becomes its expected result), follows the example rules, and repeats no example or other draft. A
   kept draft is a review item `EXAMPLE_CANDIDATE` (the draft as JSON in its details). Accepting it adds the example,
   `AI_DRAFTED` with its model, in the same transaction as the decision; a refused example (a duplicate) keeps the item
   open. It is then checked like any example. The model never writes to the pool, and nothing it writes is shown
   before a person accepts it and the check passes it.
7. **AI-drafted examples are marked.** An accepted draft is human-reviewed text, but until a lawyer confirms how
   Art. 50 treats it, the house rule applies: the public pool carries `aiDrafted` per example and an
   `AiContentMarker` (ADR 0038) when one is in it, the page labels the chip, and the review queue marks the draft
   (`data-ai-generated`). The origin and model stay on the row for good, even after an edit.

## Consequences
- I change the examples without a release, and a bad example hides itself instead of greeting a new visitor.
- What the page shows is what was checked: the same query and path, against the same ranking.
- A new or changed example shows up to 5 minutes later; the page may serve the old pool for 5 more minutes (cache).
- The check is one search per example: about a dozen searches a night, no model call, no web credit.
- An example whose expected result leaves the catalog fails the check; it is hidden, not deleted.
- A draft run costs one model call and sends no visitor data: only titles, types and descriptions of public
  resources.

## AI architecture check (the drafts)
| Layer | Status |
|---|---|
| Model | Covered: the fast Nemotron model (NVIDIA only); no model or provider down: no drafts, nothing else changes. |
| Agent framework, Tool & API, Memory, Browser | Deferred, safe: one structured call, no tools, no loop, no state between runs. |
| Orchestration | Covered: admin-started, one call, one retry for an unreadable answer, the `llm-chat` circuit. |
| Knowledge & RAG | Covered: grounded on catalog resources; a draft for a resource it was not given is dropped. |
| Data | Covered: origin `AI_DRAFTED` and model on the row; the draft in the review item. |
| Guardrails | Covered: untrusted fencing of descriptions (OWASP LLM01), JSON answer, the example rules, duplicates, the human accept, the quality check; output is never rendered as HTML (LLM05). |
| Observability | Covered: model call metrics (`example-drafts`), budget gauge, audit entry per run. |
| Evaluation | Deferred, safe: every draft is judged twice (a person, then the check); the accept rate shows prompt quality. |
| Deployment | Covered: two settings, no chart change. |
| Security & Identity | Covered: ADMIN only, admin host, CSRF, audit. |
| Human-in-the-loop | Covered: nothing reaches the pool without an accept. |
| Cost & Reliability | Covered: own daily budget (5 calls by default), circuit breaker. |

## Points for a lawyer
1. Does Art. 50(2) marking still apply to AI-drafted text once a person reviewed, maybe edited, and accepted it?
2. Does an unedited accepted draft differ from an edited one?
3. Can the human-review logic of Art. 50(4) apply by analogy to short UI text that is not public-interest
   information? Until these are answered, the house rule (label and mark) stays.

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
  check, `ExampleSearch`), `ExampleDrafts.kt` (drafts, `DraftRules`), `ExamplePorts.kt`.
- `backend/src/main/java/ai/helper2/examples/` — `usecase/` (`ListHomeExamples`, `ListAdminHomeExamples`,
  `AddHomeExample`, `ChangeHomeExample`, `RemoveHomeExample`, `CheckHomeExamples`, `DraftHomeExamples`), `config/ExamplesProperties`,
  `internal/` (`JdbcHomeExamples`,
  `SearchExampleSearch`, `CatalogResourceTitles`, `HomeExampleCheckSchedule`, `SpringAiExampleDrafter`,
  `CatalogDraftSources`, `ExamplesConfiguration`), `web/HomeExamplesController.java`; the prompt
  `backend/src/main/resources/prompts/examples/draft.txt`.
- `backend/src/main/java/ai/helper2/admin/web/HomeExamplesAdminController.java`, `admin/usecase/ResolveReview.java`
  (accept adds the drafted example), `catalog/usecase/ListWellKnownResources.java`.
- `backend/src/main/kotlin/ai/helper2/search/Search.kt` (`SearchRequest.web`), `Model.kt` (`SkipReason.WEB_NOT_ASKED`).
- `backend/src/main/resources/db/migration/V4__home_example.sql`, `V5__example_drafts.sql`.
- `frontend/src/lib/examples.ts` — the built-in pool and the pick; `frontend/src/pages/admin/ExamplesPage.tsx`.
- Tests: `examples/ExampleInputTest.kt`, `ExampleCheckTest.kt`, `search/usecase/MeteredSourceTest.kt`,
  `api/HomeExamplesApiIT.java`, `frontend/src/lib/examples.test.ts`, `frontend/src/pages/admin/ExamplesPage.test.tsx`, `examples/DraftRulesTest.kt`.

## Diagrams
[flow](../../diagrams/flow-0049-home-examples.svg) · [sequence](../../diagrams/sequence-0049-home-examples.svg)

## Roadmap
- The page reads the pool from the endpoint, with the built-in list when it does not answer in time.
- The count of hidden examples on the overview.
