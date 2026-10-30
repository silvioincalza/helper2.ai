# 0032 — Guide intents: understand the question, ask back when vague, retrieve and answer by intent

**Status.** Accepted (2026-10-28). **Builds on:** [0014](0014-llm-integration.md), [0015](0015-ai-guide-evidence-gate.md),
[0016](0016-guide-reason-check.md), [0017](0017-guide-cost-guard.md), [0030](0030-guide-evaluation.md)

## Context
The guide understands one kind of question: "find me a resource about S, with requirements R". The planner returns a
`searchQuery`, `constraints`, `wantsReading` and a `webQuery`; search ranks by relevance; the chooser picks with a
quote. Every other kind of question is forced into that shape.

Reproduced on production, 2026-10-28, question *"what are the last ai tools born in the last month?"*:

| Step | Result | Problem |
|---|---|---|
| plan | `searchQuery = "AI tools last month"`, `constraints = []` | the time window became words to match |
| search | relevance order, no date filter | candidates contain "tool" and "month"; age plays no part |
| choose | every pick rejected | status `PARTIAL` |
| answer | top 3 search results: `dsh-repeat-tool-reminder`, `@ai-sdk/mcp`, `bash-tool` | not new, not what was asked |

The same failure applies to "alternatives to Cursor" (Cursor itself ranks first), "Ollama vs LM Studio" (one pick list,
no comparison), "what is MCP?" (tools, no explanation), "when is NeurIPS?" (events are a reading type, no date in the
answer) and "AI certifications for beginners" and "a Coursera course on RAG" (the catalog has no course type). A vague question ("new tools?") is
searched as typed; the guide never asks what the user means.

What the data supports today:
- Search has `SortOrder.NEWEST` (on `published`) and `SearchFilters.updatedAfter` (on `updated`). The guide uses neither.
- `published` is the creation date for GitHub (`created_at`), Hugging Face models and Spaces (`createdAt`) and arXiv.
  For npm it is the **last publish** (`date` in the search API): an old package republished yesterday looks new.
  Tavily's `published_date` is Tavily's estimate.
- The catalog's first-discovered date is not a birth date: the catalog was seeded in the week of 2026-10-19.
- Each source returns its own top hits by relevance; filters and sort run after merging. A date filter alone only
  filters what relevance already returned.
- Events have dates and places (`ListUpcomingEvents`, [0022](0022-next-events.md)).

## Decision

### 1. Intents
The planner classifies each question into exactly one intent. One question, one intent; a question with two ("what is
Ollama and what are alternatives?") takes the first and the answer offers the second as a follow-up chip.

| Intent | Example | Retrieval | Answer |
|---|---|---|---|
| `SUGGEST` (default) | "a free tool to chat with PDFs" | today's: query + constraints, tools only | picks with quote (unchanged) |
| `NEWEST` | "AI tools born last month", "latest MCP servers" | born inside the window, newest first | picks with quote and birth date |
| `ALTERNATIVES` | "alternatives to Cursor" | find the subject X, search X's category and use cases, exclude X | picks, each with what it shares with X |
| `COMPARE` | "Ollama vs LM Studio" | find each named subject (2–3) | one column per subject, rows backed by quotes |
| `HOW_TO` | "how do I run Llama locally" | tools that do the task | picks, each with its Guide me setup link ([0031](0031-setup-guide.md)) |
| `WHAT_IS` | "what is MCP?", "what is Ollama?" | the subject's own page, then articles | 2–4 sentence explanation with quotes, the subject's card, related resources |
| `WHEN` | "when is NeurIPS?", "when was Llama 4 released?" | events by name or time; release: the subject's `published` or news | the date with its source, the card |
| `LEARN` | "AI certifications for beginners", "a course on RAG" | the new `COURSE` type (§8) | picks with platform, credential, level, cost, format |

`SUGGEST` stays the fallback: an unknown or missing intent behaves exactly as today.

### 2. Plan output
`GuideSearchPlan` gains fields; the prompt `prompts/guide/plan.txt` gets one rule block and two examples per intent.

```json
{
  "intent": "NEWEST",
  "subjects": [],
  "searchQuery": "AI tools",
  "constraints": [],
  "wantsReading": false,
  "webQuery": "new AI tools launched in the last month",
  "window": {"amount": 1, "unit": "MONTH", "direction": "PAST"},
  "clarify": null
}
```

- `subjects`: the named things the question is about, as written ("Cursor"; "Ollama", "LM Studio"). Required for
  `ALTERNATIVES` (1), `COMPARE` (2–3), `WHAT_IS` (1), `WHEN` for a release (1).
- `window`: a relative period, never a date. The model does not know today's date; code resolves it with the injected
  `Clock` (§4). `direction` is `PAST` ("last month", "this week") or `NEXT` ("events next month").
- Time words never go into `searchQuery` or `constraints`: descriptions do not contain dates, so the evidence gate
  would mark a time constraint NOT_CONFIRMED. They stay in `webQuery`, where news engines use them.
- `clarify`: see §3.

**Deterministic backup**, like `wantsReading`: regular expressions on the user's own text (English and Italian) set the
intent and window when the planner misses them, and when the model is unavailable, so the degraded answer for
"tools from last month" is the newest tools, not keyword matches. The backup only fills an empty field; it never
overrides a model value. Narrow on purpose, because a false hit changes the whole answer:
- **Window** only from an explicit time expression: `(last|past) (\d+ )?(days?|weeks?|months?|years?)`,
  `this (week|month|year)`, `(in|since) (January|…|December|20\d\d)`, Italian equivalents (`ultim[oaie] …`, `quest[oa] …`).
- `latest|newest|recent(ly)?` set the `NEWEST` intent with **sort only, no strict window**. Bare `new` sets nothing:
  "new to AI" and "a new way to…" are not about age.
- `alternatives? to|instead of|similar to`, `\bvs\.?\b|versus|compared? (to|with)`, `^what (is|are)\b|^what's|cos'è`,
  `^when (is|was|does|did)\b|quando`, `certificat(e|ion)s?|\bcourses?\b|coursera|udemy|edx|o'reilly`.
  Not `learn` or `training`: "LoRA training tool" and "learn embeddings with a library" are tool questions.

### 3. Ask back only when vague
The planner sets `clarify` when the question cannot be searched well as written:

```json
"clarify": {"question": "New tools for what?", "options": ["coding", "images and video", "agents", "chat and writing"]}
```

Rules in the prompt, with code checks:
- Only when an answer would mostly be noise: no subject and no type for `SUGGEST`/`NEWEST`/`HOW_TO` ("new tools?",
  "help me"); a subject that names several unrelated things ("Gemini": the model or the CLI?); `COMPARE` with one subject.
- Never for a question with a subject, a type or a window that retrieval can use: "AI tools born last month" is
  answered directly (window + type is enough).
- 2–4 short options, in the user's language. Code drops options over 40 characters and the clarification itself when
  fewer than 2 options remain.

Flow:
1. `AskGuide` returns status `NEEDS_CLARIFICATION` with the question and options, **after the plan call only**: no
   search, no choose, no reason check. One model call.
2. The page shows the question and the options as chips, plus **"Search anyway"**.
3. A chip sends a new guide request: the original text plus a `clarification` field with the chosen option. A free-text
   reply works the same way. "Search anyway" sends the original text with `clarification = ""`.
   `clarification` is user text like `request`: same length limit (300 characters), same `UntrustedText` wrapping in
   the planner prompt, and the prompt treats it as more of the need, never as instructions.
4. A request with a `clarification` field is never clarified again: the planner gets the instruction that the user
   already answered, and code ignores a `clarify` it returns. At most one round trip per question.
5. `NEEDS_CLARIFICATION` answers are never cached (`GuideAnswerCache.isCacheable` excludes them); the final answer is
   cached under the key of text + clarification.
6. Quota: the clarification and the follow-up are two guide requests, so they use 2 units of the per-client quota of
   [0017](0017-guide-cost-guard.md) and 1 + 3 model calls of the daily budget.

### 4. Time window
- Resolution in code: `PAST` → `[today − period, today]`, `NEXT` → `[today, today + period]`. A month is **30 days**,
  a week 7, a year 365, rolling from today (UTC). "This week"/"this month" are 7/30 days. No calendar months: on
  2 October "last month" would otherwise exclude the last 2 days.
- `SearchFilters.publishedAfter: LocalDate?` and `publishedBefore: LocalDate?`, applied on `published`. **Strict**: a
  result with unknown `published` is dropped; it cannot be called new.
- npm's `published` is the last publish, so it never counts as a birth date. `NpmSource` reports its date in `updated`
  and leaves `published` unknown. This also corrects `SortOrder.NEWEST` for npm results outside the guide.
  The catalog already holds npm rows with the wrong `published_at` (live results are recorded through
  `ResultObserver.record`). A Flyway migration moves `published_at` to `updated_at` where `published_source_id = 'npm'`
  and the row has no later `updated_at`, then sets `published_at` to null; otherwise the catalog pushdown of §5 would
  still return them as new.
- `latest`/`newest` without a period (backup rule of §2, or a plan with intent `NEWEST` and no `window`) sort by date
  but apply no strict filter.
- Tavily dates are kept (they are the date of the news item, which is what a news question needs); the card already
  marks them as estimated.
- The resolved window is in the answer (`window: {from, to}`) and in the `planned` event, so the page can show
  "Created since 28 Sep 2026, newest first".

### 5. Retrieval by intent
`AskGuide` builds the `SearchRequest` from the intent:

| Intent | Filters | Sort | Text | Other |
|---|---|---|---|---|
| `SUGGEST` | as today | relevance | `searchQuery` | — |
| `NEWEST` | `publishedAfter`, tool types (reading types if `wantsReading`) | relevance inside the window; final picks by date | `searchQuery`, soft: see below | window pushed to sources |
| `ALTERNATIVES` | tool types | relevance | X's category and use cases, from X's own card | X removed from candidates |
| `COMPARE` | none | relevance | one search per subject, the subject's own entry each | no chooser |
| `HOW_TO` | tool types | relevance | `searchQuery` | — |
| `WHAT_IS` | none | relevance | subject | candidates: subject's page first, then articles |
| `WHEN` | event: `EVENT` type + window; release: none | event: date ascending | subject | — |
| `LEARN` | `COURSE` type | relevance | `searchQuery` | — |

**Tool types** are `ResourceType.entries − READING_TYPES − COURSE`. `COURSE` is retrieved only for `LEARN`, so courses
never fill the candidate slots of a tool question.

**Window pushed to the sources.** `SourceQuery` gains `publishedAfter: LocalDate?`. Sources that can filter by date do
it themselves, so the pool is new resources, not old relevant ones filtered down to nothing. Each source's date support
is **to verify** against its API documentation before it is built; a source that turns out not to support it falls
back to the strict filter:
- Catalog (`helper2-index`, `helper2-curated`): `WHERE published_at >= ?`, ranked by text rank and popularity inside
  the window. Under our control.
- GitHub: `created:>YYYY-MM-DD` search qualifier (documented).
- Hugging Face models and Spaces: `sort=trendingScore`, then the strict filter. Checked 2026-10-28: `sort=createdAt`
  works but its newest entries are test checkpoints; trending ones are new and used.
- Tavily: `start_date` (YYYY-MM-DD), documented.
- arXiv: `submittedDate:[from TO to]` (checked 2026-10-28); browsing sorts by `submittedDate`.
- GitHub additionally sorts by stars inside a window (checked 2026-10-28), so new repositories people use come first.
- npm, semantic index: no date support; results go through the strict filter.

The per-source cache key adds the window (`…|limit|web|after=YYYY-MM-DD`). Without it a windowed query would be served
the cached unwindowed hits.

**Candidate quality inside the window.** Sorting by date and taking the first 24 would give the 24 most recently
created things, mostly empty repos. Candidates are taken by relevance and popularity **inside** the window; only the
final picks are shown newest first.

**Generic text.** For "AI tools born last month" the subject is only "AI tools". With a window:
- AI-only sources (catalog, Hugging Face, the AI-scoped Tavily query): words that name no subject ("AI", "tool(s)",
  "latest", "resources") stop being required words; the window does the retrieval and popularity ranks.
- General sources (GitHub, npm): an AI scope is kept, or the window alone returns random new repos and packages.
  GitHub gets `topic:ai OR topic:llm OR topic:mcp` style qualifiers (exact set to tune in evaluation); npm keeps
  `ai` as a required keyword.
- `AnalyzedQuery.requiredVariants` is "never empty" today. A query left with no subject words takes an explicit
  **browse path**: `SourceQuery` gets `browse = true`, the catalog runs a window + type query with no text condition,
  and sources that cannot browse are skipped for that request (reported as SKIPPED, reason "no subject to search").

**Subject resolution** (`ALTERNATIVES`, `COMPARE`, `WHAT_IS`, `WHEN` release). The top hit for "Ollama" can be an npm
wrapper (`ollama-js-helper`). A candidate is the subject when its normalized title (lower case, no punctuation,
no owner prefix such as `org/` or `@scope/`) equals the normalized subject, or equals it plus a type word ("Ollama app");
among several, the curated entry wins, then the most popular. When no candidate is the subject:
- `ALTERNATIVES`: search the subject's words as the category instead, and say "Cursor not found in our sources;
  showing tools for: AI code editor".
- `COMPARE`: the missing subject's column says "not found in our sources"; with fewer than 2 found, the answer
  falls back to `SUGGEST` on the found subject.
- `WHAT_IS`: explanation from articles only; no subject card.
- `WHEN` release: "no release date found".

### 6. Answer by intent
`GuideAnswer` gains `intent`, `window`, `clarification` and two optional blocks. The evidence gate
([0015](0015-ai-guide-evidence-gate.md)) and the reason check ([0016](0016-guide-reason-check.md)) apply to every
sentence the model writes, in every intent.

- `NEWEST`: picks as today; each card shows its birth date and the source of that date. The chooser prompt is told the
  window; the validator rejects a pick whose `published` is outside it (code check, not a model judgment).
- `ALTERNATIVES`: the reason says what the pick shares with X, with a quote from the pick. X is never a pick.
- `COMPARE`: new block `comparison`: `subjects[]` (the cards) and `rows[]` of `{aspect, cells[{subjectKey, text,
  quote}]}`. One new model call (`compare`, prompt `prompts/guide/compare.txt`) replaces `choose`. Each cell's quote
  must be in that subject's own text, checked by the gate; a failing cell shows "not stated by the source".
  Aspects: what it is, licence, price, platforms, and up to 3 the user named.
- `WHAT_IS`: new block `explanation`: `{text, quotes[{key, quote}]}`, 2–4 sentences. One model call (`explain`,
  prompt `prompts/guide/explain.txt`) replaces `choose`. Every sentence needs a quote from a candidate; sentences
  without a gated quote are dropped; with none left, the answer is the subject's card alone. Then up to 3 related picks
  from the same candidates, without a model call (search order). *Later:* the card alone, once cards carry enough text.
- `WHEN` (event): no model call for the date. The answer is the event card with start, end, place and source, from the
  event record; several matches are listed by date. `WHEN` (release): the subject's card with its `published` date and
  the date's source; with no known date, a quoted news item from the web sources, or "no release date found".
- `HOW_TO`: picks as today, each with the Guide me link of [0031](0031-setup-guide.md).
- `LEARN`: picks as today; the card shows platform, credential, level, cost and format from §8.

### 7. Cost
| Intent | Model calls (fresh question) |
|---|---|
| clarification | 1 (plan) |
| `SUGGEST`, `NEWEST`, `ALTERNATIVES`, `HOW_TO`, `LEARN` | 3 (plan, choose, verify) — as today |
| `COMPARE`, `WHAT_IS` | 3 (plan, compare/explain, verify) |
| `WHEN` (event) | 1 (plan) |

The daily budget and per-client quota of [0017](0017-guide-cost-guard.md) are unchanged.

Searches: one federated search per question, except `COMPARE`, which runs one per subject (2–3). They run in parallel
and share the existing per-source cache, but a 3-way comparison is 3× the source calls (GitHub search: 30 requests per
minute authenticated). `COMPARE` searches use `size = 5` and must fit the existing `GuideLimits` question deadline;
a subject search that misses it counts as "not found".

**Answer cache.** The cache is read before the plan call, so its key cannot hold the resolved window. Instead
`GuideAnswerCache.put` gets a per-answer TTL: answers with a window are cached for `min(H2_GUIDE_CACHE_TTL, 6h)`,
which bounds the drift of a "last month" window to 6 hours. `NEEDS_CLARIFICATION` is never cached.

**Cache invalidation on deploy.** The new prompts (`compare.txt`, `explain.txt`) are added to
`SpringAiGuideModel.getConfigFingerprint`, and the answer shape gets a schema version in the key (`v2`), so answers
cached in the old shape are not served after the deploy.

### 8. Courses and certifications: new type and sources
One type covers both, because the same question ("learn RAG", "AI certification for beginners") is answered by
either, and most platform courses end with a certificate.

- `ResourceType.COURSE` (neither a tool type nor a reading type: retrieved only for `LEARN` and through the type facet), with the specificity order, OpenAPI enum, facet label
  and badge (frontend `Badges.tsx`, `en.json`). The intent of §1 is named `LEARN` and covers "course", "certification",
  "a course on X" (the backup of §2 is narrower than the model, on purpose).
- Card fields, mapped to existing facets plus the description (no new table):
  - `platform`: the e-learning platform or vendor (Coursera, O'Reilly, edX, Udemy, DeepLearning.AI, Google…), shown as
    the source badge;
  - `credential`: `certification` (proctored exam, vendor credential), `certificate` (of completion), `none`;
  - `level`: beginner, intermediate, advanced → `techLevels`;
  - `pricing`: free, free audit, subscription, paid;
  - `format`: self-paced, instructor-led, live event, exam only;
  - `url`: the course or certification's own page.
- **Platforms covered.** Each platform is a source, with access verified before it is built; where no API is open
  to us, the platform is covered by the collector below and the curated seed.

| Platform | Access to verify | Fallback |
|---|---|---|
| Coursera | catalog API: whether it is open or partner-only | collector on `coursera.org` |
| O'Reilly (learning.oreilly.com) | search API for courses, live events, certification prep: whether it works without a subscription | collector on `oreilly.com` |
| edX | Course Discovery API: key requirements | collector on `edx.org` |
| Udemy | Affiliate API: whether new accounts are accepted | collector on `udemy.com` |
| DeepLearning.AI, Hugging Face Learn, fast.ai | no API; small, stable lists | curated seed |
| Vendor certifications: Google Cloud, AWS, Microsoft, NVIDIA DLI, IBM, Databricks | no API | curated seed + collector |

- **Source 1, curated seed** (`helper2-curated`): a reviewed YAML list of official programmes and the core courses of
  the platforms without an API. Each entry links the provider's own page; Scout enrichment
  ([0019](0019-evidence-gated-enrichment.md)) fills the description from that page.
- **Source 2, platform sources**: one `SearchSource` (live, at search time) per platform whose API access is
  confirmed, like `GitHubSource` and `NpmSource` ([0008](0008-federated-search.md)). **None today**: checked
  2026-10-28, Coursera's catalog endpoint has no search, O'Reilly's search API and edX's Discovery API answer 401
  without an enterprise or client key, and Udemy's API answers 403. Those platforms come through Sources 1 and 3.
- **Where the card fields live**: no new column. Platform is a `PLATFORM` label; level is `tech_level`; pricing is
  `pricing`, set only where the page states it; credential (`certification`, `certificate`) and format
  (`exam-only`, `self-paced`, `instructor-led`, `live-event`) are tags, also kept typed in `source_record.extra`.
  Migration `V3__course_type.sql` adds the type and the `COURSE_CANDIDATE` review kind; the seed is `R__courses.sql`.
- **Source 3, Scout collector** `CourseCollector` ([0020](0020-scout-collectors.md)): weekly Tavily searches restricted
  to each platform's domain (`include_domains`) for new AI courses and certification pages. New pages go to the admin
  review list ([0019](0019-evidence-gated-enrichment.md)), never straight to the catalog.
- **`NEWEST` on courses**: a course's `published` is its launch date where the platform states it; otherwise unknown,
  and §4's strict rule drops it from "new courses" answers.
- The guide recognises `LEARN`; normal search finds courses through the type facet.

### 9. Interface and streaming
- `GuideRequest` gains `clarification: string?` (≤ 300 characters) and `drop: string[]?` (§ "Understood as" below).
- `GuideResponse` gains `intent`, `window`, `clarify` (question + options, only with `NEEDS_CLARIFICATION`),
  `comparison`, `explanation`.
- `GuideStatus` gains `NEEDS_CLARIFICATION`.
- Stream: the `planned` event carries `intent` and `window`; a new terminal event `clarify` ends the stream after the
  plan; `compare` and `explain` reuse the `chosen` slot of the progress UI with their own label.
- The page (`GuideAnswer.tsx`) shows an "Understood as" line: intent label, subjects, window, constraints. Each part is
  a chip; clicking ✕ on one re-asks without it. The removal is structured, not text: `drop: ["window",
  "constraint:free", "intent"]` (dropping the intent means `SUGGEST`). Code applies it after the plan; the model never
  sees it, so it cannot be confused with instructions inside the user's text.

### 10. Evaluation
[0030](0030-guide-evaluation.md) gets 2 cases per intent, 16 in total, plus 3 vague questions that must clarify and 3
clear ones that must not. Pass criteria per intent: `NEWEST` — every pick born inside the window and newest first;
`ALTERNATIVES` — the subject is not a pick; `COMPARE` — one column per subject, no cell without a gated quote;
`WHAT_IS` — every sentence quoted; `WHEN` — the date equals the event record; `LEARN` — every pick has type
`COURSE`. The production question of the Context table is case `n01`.

### 11. Learned in the live check (2026-10-28, Nemotron 3 Nano plans, Nemotron 3 Super chooses)
- **Grounding.** The fast planner copies values from its examples: a one-month window on "what is RAG?", subjects
  and "works offline" on "an open source MCP server to query Postgres". Code keeps only what the user's words support:
  a model window only when the text names a time (`IntentRules.mentionsTime`), subjects only when they appear in the
  text, a constraint only when the text states it (`IntentRules.constraintStated`, fixed synonym list), NEWEST only
  with time or newness words, COMPARE with 2 grounded subjects, ALTERNATIVES and WHAT_IS with 1. The prompt's field
  formats use placeholders, and it says never to copy a value from the examples.
- **Web, AI only.** NEWEST also retrieves web announcements (NEWS, ARTICLE) and WHAT_IS puts web explainers right after
  the subject's own entry. Tavily runs as `general` search for a guide question without a period, `news` otherwise; its
  AI relevance gate and the AI-scoped web query keep it to the AI world.
- **Browsing ranks by popularity.** With a window and no subject, the search text is empty: the words "AI tools" ranked
  projects named "tool" first. Entries without a description are left out of NEWEST and WHAT_IS: they cannot be quoted.
- **Thinking for broad questions only.** With thinking off, Nemotron 3 Super returned no pick for "AI tools born last
  month" (three tries); with thinking on it picked three quoted, checked tools in about 10 s. `GuideModel.choose(…,
  think)` turns it on only for a NEWEST browse; every other question keeps thinking off (ADR 0014).
- **Dates as evidence.** The chooser sees each candidate's source-stated creation day (`created="…"`), and the reason
  check gets it as evidence, so "a new tool" is checked against that date instead of being removed.
- Result on the reproduced question: status OK, three AI picks created inside the window, newest first, every reason
  SUPPORTED. "vLLM vs SGLang" compares both, "what is retrieval augmented generation?" explains in two quoted
  sentences, "courses to learn LangChain" picks the O'Reilly course.

## Consequences
- The guide answers the kind of question asked, and shows how it read it, so a misreading is visible and correctable.
- One extra round trip only for vague questions; a clarification costs one model call.
- A broad "what is new" question takes about 10 s longer (thinking on for its choose call).
- `NEWEST` is honest but smaller: npm and other undated results are dropped, silently; the answer does not count them.
- `NpmSource` no longer reports a `published` date, and a migration clears the wrong dates already in the catalog: npm results sort last under "Newest" in normal search too.
- Two new prompts (`compare`, `explain`) and their schemas to maintain and evaluate.
- The course list needs editorial review; the collector proposes, an admin accepts. Platform APIs (Coursera, edX, Udemy) may need partner keys; until then those platforms are covered only through the collector and the seed.
- One large change across planner, search, sources, catalog, Scout, API and page; shipped as one PR on request.

## Alternatives considered
- **Keep one intent, add only a date filter** — fixes the reproduced question, not alternatives, comparisons,
  explanations, dates or certifications.
- **Let the model write the date** — it does not know today's date; code resolves a relative period instead.
- **Calendar months** — "last month" on 2 October would miss 2 days; rolling 30 days never empties at month start.
- **Keep undated results last instead of dropping them** — a result listed under "born last month" without a known
  birth date is a claim the guide cannot back.
- **Always confirm the reading before searching** — slower for every clear question; the "Understood as" chips give
  the same control after the answer.
- **One model call that classifies and answers** — mixes untrusted candidate text into the planning step and loses
  the evidence gate per sentence.
- **Courses from web search only** — no stable type or card, and every answer depends on one web source.

## Code
- `backend/src/main/kotlin/ai/helper2/guide/Guide.kt` — `GuideSearchPlan` (intent, subjects, window, clarify),
  `GuideIntent`, intent and window backups, `GuideAnswer` blocks.
- `backend/src/main/kotlin/ai/helper2/guide/usecase/AskGuide.kt` — retrieval and answer per intent, clarification.
- `backend/src/main/kotlin/ai/helper2/guide/GuideValidator.kt` (or `Guide.kt`) — window check, comparison and
  explanation gates.
- `backend/src/main/java/ai/helper2/guide/internal/SpringAiGuideModel.java` — `compare`, `explain`, plan schema.
- `backend/src/main/resources/prompts/guide/plan.txt`, `choose.txt`, new `compare.txt`, `explain.txt`.
- `backend/src/main/kotlin/ai/helper2/search/Filtering.kt` — `publishedAfter`, `publishedBefore`.
- `backend/src/main/kotlin/ai/helper2/search/Ports.kt`, `usecase/SearchResources.kt` — `SourceQuery.publishedAfter`,
  cache key.
- `backend/src/main/java/ai/helper2/sources/*Source.java`, `catalog/CatalogSearchSource.java`, `ResourceStore` — date
  pushdown; `NpmSource` date fix.
- `backend/src/main/kotlin/ai/helper2/shared/Vocabulary.kt` — `ResourceType.COURSE`.
- Scout: `CourseCollector`, curated seed file; `sources/` — one source per platform with confirmed API access.
- `api/openapi.yaml` — request, response, status, stream events.
- `frontend/src/components/GuideAnswer.tsx`, `GuideProgress.tsx`, `Badges.tsx`, `pages/HomePage.tsx`,
  `i18n/locales/en.json` — clarification chips, "Understood as", comparison table, explanation, course badge.

## Diagrams
[flow](../diagrams/flow-0032-guide-intents.svg) · [sequence](../diagrams/sequence-0032-guide-intents.svg)
