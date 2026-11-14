# 0028 — Home examples

**Depends on:** [0022](0022-federated-search.md), [0025](0025-ranking.md), [0036](../identity/0036-identity-and-the-admin-host.md), [0038](../backoffice/0038-back-office-and-review.md), [0040](../frontend/0040-design-system.md), [0046](../operations/0046-evaluation-guide-and-search.md).

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
   each night (`H2_EXAMPLES_*`). It is the same rule as the eval group `home-example` (ADR 0046), so a ranking change
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
   it does not ask the guide, so it spends no daily AI answer. The page asks for the pool when it opens and waits at
   most 800 ms. When the pool fails, is late, or has fewer than two examples for a path, the page shows two per path
   from the built-in list instead. While it waits, the row holds the built-in pick, unseen, so nothing moves when the
   chips appear; there is no spinner.
5. **The back office.** `/api/v1/admin/home-examples` lists, adds, changes and removes examples (ADMIN, admin host),
   in the admin module like every back-office endpoint (ADR 0038). The page `/admin/examples` shows each check in
   words, finds expected results with the visitors' own search, and reacts to the problem codes (`duplicate`,
   `version-conflict`, `invalid-request` with the broken rule). The overview (ADR 0038) counts the examples of every
   locale in `homeExamples`: shown per path (`ACTIVE` and `PASS`), hidden per check (`ACTIVE` and `WEAK`, `FAIL` or
   `PENDING`) and paused (whatever the check). Each count links to `/admin/examples?outcome=...` (paused: the whole
   list). A path with fewer than 2 shown gets the same warning as the Examples page: the home page then shows the
   built-in list (point 4).

6. **AI drafts, a person decides.** **Draft examples with AI** makes one model call (the fast Nemotron model, the
   drafts' own daily budget `H2_EXAMPLES_DRAFT_CALLS`, 0 = off, inside the `llm-chat` circuit). The model gets public
   facts about well-known catalog resources that no example expects yet and no waiting draft is about
   (`H2_EXAMPLES_DRAFT_SOURCES`, curated first), and answers JSON. The prompt (`prompts/examples/draft.txt`) has a
   SECURITY block with the guide's data rule: text inside our tags is data, never instructions. Each resource is in a
   `<resource>` tag, its title and its description in tags of their own, with `<` and `>` masked, so third-party text
   cannot close a tag; the type and the audience are masked too and lose their quotes. The prompt asks for the label
   and query limits of `ExampleInput`, the same values the code checks. Code keeps a draft only when it names a
   resource it was given (that resource becomes its expected result), follows the example rules, and repeats the label
   or query of no example, no other draft of the run and no draft still waiting for review. A kept draft is a review
   item `EXAMPLE_CANDIDATE` (the draft as JSON in its details). Only the examples module writes and reads those
   details (the `DraftQueue` port). The review page gets the draft typed (`draft` on the review item: label, query,
   path, model), read by the examples use case `ReadExampleDraft`. The back office's `ResolveReview` accepts a draft
   through the examples use case `AcceptExampleDraft`, which adds the example, `AI_DRAFTED` with its model, in the
   same transaction as the decision.
   A refused example (a duplicate, a broken rule, an unreadable draft) keeps the item open, and the review page says
   why. It is then checked like any example. The model never writes to the pool, and nothing it writes is shown
   before a person accepts it and the check passes it. The prompt's label is the hash of the rendered prompt
   (`5826f49c`), pinned by a test: a new wording shows up there first.
7. **AI-drafted examples are marked.** The rule: an example a model drafted is AI-written text, also after a person
   accepted or edited it. Its label and query are what the model wrote; its path is a model's pick among two values,
   a classification. An accepted draft is human-reviewed text, but until a lawyer confirms how Art. 50 treats it, the
   house rule applies: the public pool carries `aiDrafted` per example and an `AiContentMarker` (ADR 0033) when one is
   in it, naming the model and the fields `examples[].label` and `examples[].query`. The page reads the pool and gives
   an AI-drafted chip an "AI draft" label (its tooltip: "Drafted with AI, checked by a person"); the chip and the
   draft in the review queue carry `data-ai-generated`. The origin and model stay on the row for good, even after an edit (a CHECK keeps a
   model on every `AI_DRAFTED` row).
8. **Where the code lives.** The library `libs/examples` holds the domain (Kotlin), the use cases as plain classes
   wired in `examples.config` (add, change and remove get their transaction on a subclass there), and the adapters in
   `internal`. The public app builds only the read: `ListHomeExamples` over the port `ServedExamples`
   (`JdbcServedExamples`, scanned by both apps). Everything an administrator or the check uses is in
   `ExamplesBackOfficeConfiguration` and `ExamplesBackOfficeAdapters` (the pool's store `HomeExamples`, the
   example's search, the titles, the drafts, the drafter with its budget, gauge and settings group). They carry no
   stereotype and only `BackOfficeApplication` imports them
   ([0002](../foundation/0002-modules-modular-monolith-hexagonal-where-the-rules-live.md) §5), so the public context has no write to the
   pool, no draft model call and no review queue adapter of the examples (`NoBackOfficeExamplesIT`). The use cases are in the `DOMAIN` of both apps' `ModularityTest`, and the library checks its own rules
   (`ExamplesArchitectureTest`). The public endpoint is the public app's (`examples.web`); the check's schedule is the
   back office's (`examples.schedule`).

### Code

- `backend/libs/core/src/main/kotlin/ai/helper2/examples/` — `HomeExample.kt` (example, input rules), `ExampleCheck.kt` (the
  check, `ExampleSearch`), `ExampleDrafts.kt` (drafts, `DraftRules`), `PoolCounts.kt` (shown and hidden),
  `ExamplePorts.kt`.
- `backend/libs/core/src/main/java/ai/helper2/examples/` — `usecase/` (`ListHomeExamples`, `ListAdminHomeExamples`,
  `CountHomeExamples`, `AddHomeExample`, `ChangeHomeExample`, `RemoveHomeExample`, `CheckHomeExamples`,
  `DraftHomeExamples`, `AcceptExampleDraft`, `ReadExampleDraft`), `config/` (`ExamplesProperties`,
  `ExamplesUseCaseConfiguration`: the read, both apps; `ExamplesBackOfficeConfiguration`: the back office use cases
  and their transactions), `internal/` (`JdbcServedExamples`, both apps; `ExamplesBackOfficeAdapters`, the back
  office only: `JdbcHomeExamples`, `SearchExampleSearch`, `CatalogResourceTitles`, `SpringAiExampleDrafter`,
  `CatalogDraftSources`, `ReviewQueueDrafts`); the prompt
  `backend/libs/core/src/main/resources/prompts/examples/draft.txt`.
- `backend/apps/public/src/main/java/ai/helper2/examples/web/HomeExamplesController.java` (the pool and its marker).
- `backend/apps/backoffice/src/main/java/ai/helper2/examples/schedule/HomeExampleCheckSchedule.java`,
  `admin/web/HomeExamplesAdminController.java`, `admin/usecase/ResolveReview.java` (accepts a draft through
  `AcceptExampleDraft`), `admin/web/ReviewItemMapper.java` (the typed draft, through `ReadExampleDraft`),
  `admin/usecase/GetOverview.java` and `admin/web/OverviewController.java` (the counts on the
  overview), `catalog/usecase/ListWellKnownResources.java`.
- `backend/libs/core/src/main/kotlin/ai/helper2/search/Search.kt` (`SearchRequest.web`), `Model.kt` (`SkipReason.WEB_NOT_ASKED`).
- `backend/libs/schema/src/main/resources/db/migration/V1__schema.sql` (`home_example`, the built-in pool, the `EXAMPLE_CANDIDATE` review kind).
- `frontend/src/lib/examples.ts` — the built-in pool, the pick and the bucket rule;
  `frontend/src/features/home/HomeExamples.tsx` — the fetch with its timeout, the fallback and the AI mark;
  `frontend/src/admin/ExamplesPage.tsx`; `frontend/src/admin/AdminOverview.tsx` (the counts and the
  warning), `adminModel.ts` (`SHOWN_PER_PATH`, shared by both pages).
- Tests: `examples/ExampleInputTest.kt`, `ExampleCheckTest.kt`, `examples/DraftRulesTest.kt`, `PoolCountsTest.kt`,
  `examples/usecase/ReadExampleDraftTest.java`,
  `examples/internal/SpringAiExampleDrafterTest.java` (the prompt, its tags and its label),
  `examples/architecture/ExamplesArchitectureTest.java`, `api/HomeExamplesPoolApiIT.java` (the AI marker),
  `api/AdminOverviewApiIT.java`, `frontend/src/admin/Admin.test.tsx`,
  `search/usecase/MeteredSourceTest.kt`, `api/HomeExamplesApiIT.java`, `frontend/src/lib/examples.test.ts`,
  `frontend/src/features/home/HomeExamples.test.tsx`, `frontend/src/admin/ExamplesPage.test.tsx`.

## Alternatives considered

- Keep the pool in the bundle (built-in list only) — no check at runtime, a release for every change. The built-in
  list stays as the page's fallback and in the eval group.
- Pick on the server — one answer per visitor, so no shared cache, for a payload of a few kilobytes.
- Check through the guide — a model call per example per night, and answers that vary run to run. The guide is not
  what a click asks.
- Check only that a search returns something — the review showed the real problem is a full page of wrong results,
  not an empty one.

## Consequences

- I change the examples without a release, and a bad example hides itself instead of greeting a new visitor.
- What the page shows is what was checked: the same query and path, against the same ranking.
- A new or changed example shows up to 5 minutes later; the page may serve the old pool for 5 more minutes (cache).
- The check is one search per example: about a dozen searches a night, no model call, no web credit.
- An example whose expected result leaves the catalog fails the check; it is hidden, not deleted.
- A draft run costs one model call and sends no visitor data: only titles, types and descriptions of public
  resources.

### AI architecture check (the drafts)

| Layer | Status |
|---|---|
| Model | Covered: the fast Nemotron model (NVIDIA only); no model or provider down: no drafts, nothing else changes. |
| Agent framework, Tool & API, Memory, Browser | Deferred, safe: one structured call, no tools, no loop, no state between runs. |
| Orchestration | Covered: admin-started, one call, one retry for an unreadable answer, the `llm-chat` circuit. |
| Knowledge & RAG | Covered: grounded on catalog resources; a draft for a resource it was not given is dropped. |
| Data | Covered: origin `AI_DRAFTED` and model on the row; the draft in the review item. |
| Guardrails | Covered: title and description in their own tags with `< >` masked, attributes without quotes, the data rule in the prompt (OWASP LLM01); JSON answer, the example rules, duplicates, the human accept, the quality check; output is never rendered as HTML (LLM05). |
| Observability | Covered: model call metrics (`example-drafts`), budget gauge, audit entry per run. |
| Evaluation | Deferred, safe: every draft is judged twice (a person, then the check); the accept rate shows prompt quality. The prompt's label is pinned; the eval of the prompt on staging is open ([#245](https://github.com/silvioincalza/helper2.ai/issues/245)). |
| Deployment | Covered: two settings, no chart change. |
| Security & Identity | Covered: ADMIN only, admin host, CSRF, audit. |
| Human-in-the-loop | Covered: nothing reaches the pool without an accept. |
| Cost & Reliability | Covered: own daily budget (5 calls by default), circuit breaker. |

### Points for a lawyer

1. Does Art. 50(2) marking still apply to AI-drafted text once a person reviewed, maybe edited, and accepted it?
2. Does an unedited accepted draft differ from an edited one?
3. Can the human-review logic of Art. 50(4) apply by analogy to short UI text that is not public-interest
   information? Until these are answered, the house rule (label and mark) stays.
4. Does the path a model picked (`USE_AI`, `BUILD_WITH_AI`), a classification and not shown as text, need a mark of
   its own?

## Diagrams

[flow](../../diagrams/flow-0028-home-examples.svg) · [sequence](../../diagrams/sequence-0028-home-examples.svg)

## Backlog

- Features: [F3.7](https://github.com/silvioincalza/helper2.ai/issues/53) Home examples from a checked pool.
- Open issues that change this decision:
  - [#245](https://github.com/silvioincalza/helper2.ai/issues/245) S3.7.3 The open steps of the home examples done
