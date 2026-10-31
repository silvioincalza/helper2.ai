# 0032 — Guide intents: understand the question, ask back when vague or ambiguous, retrieve and answer by intent

**Status.** Accepted. **Builds on:** [0014](0014-llm-integration.md), [0015](0015-ai-guide-evidence-gate.md),
[0016](0016-guide-reason-check.md), [0017](0017-guide-cost-guard.md), [0030](../quality/0030-guide-evaluation.md)

## Context
People ask the guide different kinds of questions. "A free tool to chat with PDFs" wants picks. "What are the last AI
tools born in the last month?" wants new things, so age matters more than words. "Alternatives to Cursor" must not
answer Cursor. "Ollama vs LM Studio" wants a comparison, "what is MCP?" an explanation, "when is NeurIPS?" a date, and
"AI certifications for beginners" a course.

One pipeline for all of them (search the words by relevance, choose picks with a quote) would answer them badly. For
the "born in the last month" question it would search "AI tools last month" with no date filter, so the candidates
would contain "tool" and "month", the chooser would reject them all, and the answer would be three old packages. A
vague question ("new tools?") would be searched as typed. A name can mean things of different kinds: "Gemini" is a model family and a
command-line tool, and an answer about the wrong one is worse than a short question. An event shares its name with
projects ("NeurIPS", "AI hackathon"), so a date question searched over every type can lose the event among them.

What the data supports:
- Search has `SortOrder.NEWEST` (on `published`) and the filters `publishedAfter` and `publishedBefore`.
- `published` is the creation date for GitHub (`created_at`), Hugging Face models and Spaces (`createdAt`) and arXiv.
  npm's date is the last publish, so `NpmSource` reports it as `updated` and leaves `published` unknown. Tavily's
  `published_date` is Tavily's estimate. The catalog's first-discovered date is not a birth date.
- Each source returns its own top hits by relevance; filters and sort run after merging, so a date filter alone only
  filters what relevance returned.
- Events have dates and places ([0022](../scout/0022-next-events.md)).

## Decision

### 1. Intents
The planner puts each question into exactly one intent. One question, one intent.

| Intent | Example | Retrieval | Answer |
|---|---|---|---|
| `SUGGEST` (default) | "a free tool to chat with PDFs" | the planned query, tool types | picks with quote |
| `NEWEST` | "AI tools born last month", "latest MCP servers" | created inside the window, newest first | picks with quote and creation date |
| `ALTERNATIVES` | "alternatives to Cursor" | the subject's own entry, then its use cases and type; the subject excluded | picks |
| `COMPARE` | "Ollama vs LM Studio" | each named subject's own entry (2–3) | one column per subject, cells backed by quotes |
| `HOW_TO` | "how do I run Llama locally" | the planned query, tool types | picks |
| `WHAT_IS` | "what is MCP?" | the subject's own entry, then web explainers | 2–4 quoted sentences, the subject's card, related cards |
| `WHEN` | "when is NeurIPS?", "when was Llama 4 released?" | events only for an event; else the subject's name | an event's dates, or the subject's creation date, with their source |
| `LEARN` | "AI certifications for beginners", "a course on RAG" | the `COURSE` type (§8) | picks |

An unknown or missing intent is `SUGGEST`.

### 2. Reading the question (`QuestionReader`)
The plan call (fast model, `prompts/guide/plan.txt`) returns:

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

- `subjects`: the named things the question is about, as written.
- `window`: a relative period, never a date. The model does not know today's date; code resolves it (§4).
  `direction` is `PAST` ("last month") or `NEXT` ("events next month").
- Time words stay out of `searchQuery` and `constraints`: descriptions do not contain dates, so the evidence gate
  would mark a time constraint NOT_CONFIRMED. They go into `webQuery`, where news engines use them.

**Grounding.** The fast planner copies values from its examples, so code keeps only what the user's own words support:
- subjects that appear in the text, at most 3, each at most 60 characters and without a link;
- a model window only when the text names a time (`IntentRules.mentionsTime`);
- a model intent only when grounded: `NEWEST` with time or newness words, `COMPARE` with 2 grounded subjects,
  `ALTERNATIVES` and `WHAT_IS` with 1;
- a constraint only when the text states it (`IntentRules.constraintStated`, a fixed word list per kind of requirement).

**Deterministic backup** (`IntentRules`, English and Italian word lists in `i18n/intent-words_*.properties`). When the
plan has no grounded intent or window, or there is no model, rules on the user's text set them, so the degraded answer
for "tools from last month" is still the newest tools. Narrow on purpose, because a false hit changes the whole answer:
- the intent, most specific shape first: comparison words, then alternatives, courses and certifications, "when",
  a period or "latest/newest/recent", "what is", "how to", then "new" right before a kind of resource (so
  "how to set up new MCP servers" stays `HOW_TO`);
- a window only from an explicit time expression ("last 3 weeks", "next month", "this year", "today");
- "latest" alone sets `NEWEST` with sort only, no window. "new" sets `NEWEST` only right before a kind of resource,
  with at most one word between ("new MCP servers", "any new open-source models", "nuovi modelli"). Elsewhere it sets
  nothing: "new to AI", "a new way to…", "a new project with MCP servers" are not about age.

The planner's `SUGGEST` is its "nothing special": when the rules read `NEWEST` in the user's words, `NEWEST` wins
("new MCP servers for Postgres" came back as `SUGGEST`). Any other planner intent stays. A period with intent `SUGGEST`
becomes `NEWEST`. The user's removals (§9) apply last: a dropped intent is `SUGGEST`, a
dropped window or constraint is gone. When the plan call succeeded the stream sends `planned` (search query,
constraints, model); then, always, `understood` (intent, subjects, window).

### 3. Ask back when there is nothing to search, or the subject is ambiguous
The planner may return `clarify`: a question and 2–4 short options in the user's language. `Clarification.of` keeps a
question of at most 120 characters and options of at most 40, without links; with fewer than 2 options left there is
no clarification. Code decides whether a question is asked back; the planner only words it.

**Nothing to search** (`QuestionReader.clarificationOf`, before any search). The planner's `clarify` is kept when:
- `COMPARE` has fewer than 2 grounded subjects, or
- there is nothing to search: no subject, no window, and no subject words left in the query once generic words
  ("AI", "tools", "latest") are removed (`IntentRules.subjectWords`).

**Ambiguous subject** (`AmbiguousSubject`, after the question's own search). The question is asked back when all hold:
- the plan call succeeded and the request carries no clarification;
- the question does not name the kind of thing it means ("the Gemini model", "the Gemini CLI tool";
  `IntentRules.namesKind`, `subject.kind` in the word bundles);
- exactly one grounded subject, and the question is about that thing itself: `WHAT_IS`, `ALTERNATIVES`, `WHEN` not
  about an event (§5), or `SUGGEST` whose text is only the name and generic words ("Gemini", not "a chat UI for
  Gemini") (`QuestionReader.askBackSubject`);
- the search the intent runs anyway finds the subject's own entries (`isSubject`, §5) of at least two kinds
  (`SubjectKind`): `MODEL`, `DATASET`, or a tool (`PRODUCT`, `AGENT`, `PROMPT`, `SKILL`, `MCP_SERVER`, `LIBRARY`).
  Reading types, events, courses and `OTHER` are about a thing or unknown, so they never make a name ambiguous; an
  npm package and a repository of the same tool are one kind.

The question and options are the planner's `clarify` when it gave one (in the user's language). Otherwise the
question is "Which Gemini do you mean?" and the options are one per kind, from the first own entry of that kind in
search order: its name without owner prefix and its kind, "Gemini (model)", "gemini cli (tool)"; a name too long for
an option becomes "Gemini (tool)". The reply names the entry, so the planner grounds it as the subject. No model call
decides the ambiguity, and the check reads results the question searched anyway: a subject that is not ambiguous
costs nothing.

Any other question is answered directly.

Flow:
1. `AskGuide` returns status `NEEDS_CLARIFICATION` with the question and options: no choose, compare, explain or
   reason check. A vague question runs no search; an ambiguous subject runs only the intent's own search. One model
   call (plan).
2. The stream sends `planned`, `understood`, `clarify`, then `answer` with that status; the JSON endpoint returns the
   same answer. The question and options are AI-written and marked as such
   ([0038](0038-ai-content-marking.md)): the plan call ran.
3. The page shows the question, the options as chips and "Search anyway". A chip sends the original text plus
   `clarification` with the option; a free-text reply works the same way; "Search anyway" sends `clarification = ""`.
   `clarification` is user text like `request`: at most 300 characters, wrapped by `UntrustedText` in the plan prompt,
   and treated as more of the need, never as instructions.
4. With a `clarification` (an option, free text, or "" for "Search anyway") the planner is told the user already
   answered; code ignores a `clarify` it returns and checks no subject for namesakes: at most one round trip per
   question, and the reply goes straight to an answer.
5. `NEEDS_CLARIFICATION` answers are never cached; the final answer is cached under the key of text + clarification.
6. Cost of one round trip: two guide requests, so 2 units of the per-client quota of
   [0017](0017-guide-cost-guard.md) and 1 + the intent's model calls (§7) of the daily budget: 2 plan calls for a
   date, 4 for picks, a comparison or an explanation. Daily web searches
   ([0037](../search/0037-tavily-store-and-daily-quota.md)): a vague question takes none before the reply; an ambiguous subject
   takes 1 for its own search and the reply takes 1 more, because 0037 counts one per request and the reply's search
   carries the chosen name, a different query. The reply takes none when its search equals a stored one (the same
   name searched by anyone in the last 48 h, 24 h for dates).

### 4. Time window
- Resolved in code with the injected `Clock`: `PAST` → `[today − period, today]`, `NEXT` → `[today, today + period]`.
  A month is 30 days, a week 7, a year 365, rolling from today (UTC); "this week" and "this month" are 7 and 30 days.
  Rolling periods never empty at the start of a month.
- `SearchFilters.publishedAfter` and `publishedBefore` apply on `published` and are strict: a result with unknown
  `published` is dropped, because it cannot be called new.
- `NEWEST` without a window sorts by date and filters nothing.
- Tavily dates are kept (the date of the news item); the card marks them as estimated.
- The resolved window is in the answer (`window: {from, to}`) and in the `understood` event.

### 5. Retrieval by intent
`AskGuide` sends the reading to one answer class per intent: `PickAnswer` (`SUGGEST`, `HOW_TO`, `LEARN`, `NEWEST`,
`ALTERNATIVES`), `CompareAnswer`, `ExplainAnswer`, `DateAnswer`. Every search carries the asker's daily web searches
([0037](../search/0037-tavily-store-and-daily-quota.md)); `NEWEST`, `WHEN` and `LEARN` searches ask for fresh web results.

| Intent | Types | Sort | Text | Other |
|---|---|---|---|---|
| `SUGGEST`, `HOW_TO` | tool types; every type but courses when the user asks for reading | relevance | planned query | — |
| `NEWEST` | tool types plus `NEWS` and `ARTICLE` (all reading types if asked), window | relevance inside the window; no window: newest | subject words; empty with a window and no subject (browse) | entries without a description left out; picks shown newest first |
| `ALTERNATIVES` | tool types | relevance | the subject's use cases and type, from its own entry | the subject never a candidate |
| `COMPARE` | all | relevance | each subject's name, size 5 | the searches run at the same time |
| `WHAT_IS` | all | relevance | the subject (or the query) | candidates with a description: the subject's own entry, then web explainers, at most 12 |
| `WHEN`, about an event | `EVENT` | relevance; soonest first in the answer | the subject, else the subject words, else "AI" | planned web query; no model call |
| `WHEN`, other | all | relevance | the subject, else the subject words, else "AI" | no model call |
| `LEARN` | `COURSE` | relevance | planned query | window if stated |

**About an event.** A `WHEN` question is about an event when the user's words or the planned search query name a
kind of event (`IntentRules.namesEvent`, `when.event` in the word bundles: conference, hackathon, meetup, summit,
webinar, workshop, deadline, and their Italian forms). A start ("start", "begins", `when.start`) names an event only
next to a course word ("when does the fast.ai course start?"): "when did Ollama start?" asks for a release date. The planner names the kind of event in `searchQuery`
for "when is NeurIPS?" ("NeurIPS conference") and never for a release ("Llama 4 release"). Its search keeps only
`EVENT` results, so a repository named like the event never takes an event's place in the result list.

**Tool types** are every type but the reading types (`ARTICLE`, `PAPER`, `NEWS`, `EVENT`) and `COURSE`, so courses
never fill the candidates of a tool question.

**Window pushed to the sources.** `SourceQuery.publishedAfter` carries the window's start to sources that can filter
by date (`SourceDescriptor.filtersByDate`): the catalog (`published_at >=`), GitHub (`created:>=`, sorted by stars
inside the window), Tavily (`start_date`). The others go through the strict filter. The per-source cache key includes
the window, so a windowed query is never served unwindowed hits.

**Browse.** With a window and no subject words, the search text is empty and `SourceQuery.browse` is set: sources
that can list by date and type alone (`SourceDescriptor.browses`) rank by popularity inside the window; the others are
SKIPPED for that request. The chooser thinks before answering only for this browse (`GuideModel.choose(…, think)`):
with thinking off, Nemotron 3 Super finds no pick for a question such as "AI tools born last month".

**Subject resolution** (`isSubject`, for `ALTERNATIVES`, `COMPARE`, `WHAT_IS`, `WHEN`). A result is the subject when
its title or the last segment of its link, in lower case without punctuation or owner prefix (`org/`, `@scope/`),
equals the subject, or the subject plus a type word ("Ollama app"). The first such result in search order wins. When
none is the subject:
- `ALTERNATIVES`: the planned query is searched instead, with the notice "Cursor was not found in our sources; showing
  tools for: …" (`GUIDE_SUBJECT_NOT_FOUND`).
- `COMPARE`: a subject whose search finds no own entry, fails or misses its deadline (10 s for all subjects
  together) is not found. With fewer than 2 found, the answer is the picks of the found subject (or of the query),
  with one `GUIDE_SUBJECT_NOT_FOUND` notice per missing subject.
- `WHAT_IS`: the explanation draws on the other candidates; the cards are related ones only.
- `WHEN`: see §6.

### 6. Answer by intent
The evidence gate ([0015](0015-ai-guide-evidence-gate.md)) and the reason check ([0016](0016-guide-reason-check.md))
apply to every sentence the model writes, in every intent. The comparison and explanation limits are one
`AnswerLimits`, rendered into their prompts and part of the answer cache key.

- `SUGGEST`, `HOW_TO`, `LEARN`: picks. Every card carries its Guide me link ([0031](0031-setup-guide.md)); a course
  card shows platform, credential, level, cost and format (§8).
- `NEWEST`: picks newest first. The chooser and the reason check see each candidate's creation day as stated by its
  source (`created="…"`), so "a new tool" is checked against that date. A pick created outside the window is rejected
  by code (`OUTSIDE_WINDOW`), not left to the model.
- `ALTERNATIVES`: picks; the subject is never one.
- `COMPARE`: block `comparison`: `subjects[]` (each name with its entry) and `rows[]` of
  `{aspect, cells[{subjectKey, text, quote}]}`. One model call (`compare`, `prompts/guide/compare.txt`), not
  choose, fills the rows. Rows: what it is, licence, price, platforms, then up to 3 aspects the user named; at most 8 rows, aspects of
  at most 40 characters. A cell is kept only with a text of at most 200 characters, no link, and a quote of at least 5
  characters found in that subject's own text; otherwise it reads "not stated by the source". A cell the reason
  check rejects is emptied (`REMOVED`). The recommendations are the subjects' own entries.
- `WHAT_IS`: block `explanation`, written by one model call (`explain`, `prompts/guide/explain.txt`): up to 4
  sentences of at most 300 characters, each with a quote from a candidate that
  passes the evidence gate; a sentence without one, or one the reason check rejects, is dropped. The cards: the
  subject's own entry first, then related candidates in search order, 4 in all, without a model call. With no
  sentence left, the cards alone (`PARTIAL`).
- `WHEN`: dates come from records, never from a model. Events among the results (type `EVENT` with a start date,
  the subject in the title when there is one) inside the window, or not yet over without a window: up to 5, soonest
  first, each with start, end, place and the source of the date. The window applies to the event's days, not to
  its creation date. Else, for a question that is not about an event, the subject's own non-event entry with its
  creation date and that date's source, or "No release date found in our sources." (`PARTIAL`). Else the top
  results with the notice "No date found in our sources; showing the closest search results." (`PARTIAL`), or
  `NO_CANDIDATES`.

### 7. Cost and latency
| Intent | Model calls (fresh question) |
|---|---|
| clarification (vague or ambiguous) | 1 (plan) |
| `SUGGEST`, `NEWEST`, `ALTERNATIVES`, `HOW_TO`, `LEARN` | 3 (plan, choose, verify) |
| `COMPARE`, `WHAT_IS` | 3 (plan, compare or explain, verify) |
| `WHEN` | 1 (plan) |

The daily budget and the per-client quota of [0017](0017-guide-cost-guard.md) apply to all of them.

Searches: one federated search per question, except `COMPARE`, which runs one per subject (2–3) at the same time, on
virtual threads that carry the question's trace. They share the per-source cache; a 3-way comparison is 3 times the
source calls (GitHub search: 30 requests per minute authenticated).

**Answer cache.** The cache is read before the plan call, so its key cannot hold the resolved window. Answers with a
window live `min(H2_GUIDE_CACHE_TTL, 6 h)`, which bounds the drift of a "last month" window to 6 hours. The key has
the answer shape's version (`v2`) and the config hash covers every prompt, including `compare.txt` and `explain.txt`.

**Steps measured.** Each step of a question (`plan`, `search`, `choose`, `compare`, `explain`, `validate`, `verify`)
is a child span and timer `helper2.guide.step{step,intent}`, and one `guide timings` log line per question lists the
step times and what each step found.

### 8. Courses and certifications: one type and its sources
One type covers both, because the same question ("learn RAG", "AI certification for beginners") is answered by
either, and most platform courses end with a certificate.

- `ResourceType.COURSE`: neither a tool type nor a reading type; retrieved only for `LEARN` and through the type facet.
- Card fields, on existing columns (no new table): platform is a `PLATFORM` label shown as the source badge; level is
  `tech_level`; pricing is `pricing`, set only where the page states it; credential (`certification`, `certificate`)
  and format (`exam-only`, `self-paced`, `instructor-led`, `live-event`) are tags, also kept typed in
  `source_record.extra`. Migration `V3__course_type.sql` adds the type and the `COURSE_CANDIDATE` review kind.
- **Curated seed** (`R__courses.sql`): official programmes and the core courses of platforms without an open API
  (DeepLearning.AI, Hugging Face Learn, fast.ai, vendor certifications of Google Cloud, AWS, Microsoft, NVIDIA DLI,
  IBM, Databricks). Each entry links the provider's own page.
- **No platform source.** Coursera's catalog endpoint has no search, O'Reilly's search API and
  edX's Discovery API answer 401 without an enterprise or client key, and Udemy's API answers 403.
- **Scout collector** `CourseCollector` ([0020](../scout/0020-scout-collectors.md)): Tavily searches restricted to each
  platform's domain for new AI courses and certification pages. New pages go to the admin review list
  ([0019](../scout/0019-evidence-gated-enrichment.md)), never straight to the catalog.
- A course's `published` is its launch date where the platform states it; otherwise unknown, and §4's strict rule
  drops it from "new courses" answers.

### 9. Interface and streaming
- `GuideRequest`: `clarification` (at most 300 characters) and `drop` (below).
- `GuideResponse`: `intent`, `subjects`, `window`, `clarification` (only with `NEEDS_CLARIFICATION`), `comparison`,
  `explanation`. `GuideStatus` has `NEEDS_CLARIFICATION`.
- Stream: `planned` (search query, constraints, model) when the plan call succeeded; `understood` (intent, subjects,
  window) for every fresh question; `clarify` before an `answer` with `NEEDS_CLARIFICATION`; `compare` and `explain`
  report through the `chosen` and `validated` events of the progress view.
- The page shows an "Understood as" line: intent, subjects, window, constraints, each as a chip. ✕ on a chip re-asks
  without it. The removal is structured, not text: `drop: ["window", "constraint:free", "intent"]`. Code applies it
  after the plan; the model never sees it, so it cannot be confused with instructions in the user's text.

### 10. Evaluation
[0030](../quality/0030-guide-evaluation.md) runs the guide question set. Pass criteria per intent: `NEWEST` — every pick created
inside the window, newest first; `ALTERNATIVES` — the subject is not a pick; `COMPARE` — one column per subject, no
cell without a gated quote; `WHAT_IS` — every sentence quoted; `WHEN` — the date equals the event record, and a
question about an event shows events only; `LEARN` — every pick has type `COURSE`; ask-back cases — asked back
exactly when expected ("new tools?", "Gemini", "what is Gemini?" are; "what is Ollama?" is not).

## Consequences
- The guide answers the kind of question asked and shows how it read it, so a misreading is visible and correctable.
- A question is asked back only when it has nothing to search or its one subject has own entries of different kinds.
  The evaluation set carries ask-back cases both ways.
- Dates come from records, never from a model.
- A `NEWEST` answer lists only results with a known creation date, so "born last month" is always backed.
- A comparison runs its subject searches at the same time.
- New courses reach the catalog only through the admin review list: the collector proposes, an admin accepts.

## Alternatives considered
- **One intent with a date filter** — fixes "born last month", not alternatives, comparisons, explanations, dates or
  certifications.
- **Let the model write the date** — it does not know today's date; code resolves a relative period instead.
- **Calendar months** — "last month" on 2 October would miss 2 days; rolling 30 days never empties at month start.
- **Keep undated results last instead of dropping them** — a result listed under "born last month" without a known
  birth date is a claim the guide cannot back.
- **Always confirm the reading before searching** — slower for every clear question; the "Understood as" chips give
  the same control after the answer.
- **Let the planner decide that a name is ambiguous** — it does not know our sources and copies examples, so it would
  ask about names we hold only one kind of; a second model call to judge it costs a call on every subject question.
  Code decides on the entries, the planner only words the question.
- **A separate lookup search for the ambiguity check** — one more search, and one more web credit, on every subject
  question; the intent's own search already holds the subject's entries.
- **Search every type for an event and filter afterwards** — repositories named like the event fill the result list
  before the events.
- **One model call that classifies and answers** — mixes untrusted candidate text into the planning step and loses
  the evidence gate per sentence.
- **Courses from web search only** — no stable type or card, and every answer depends on one web source.

## Code
- `backend/src/main/kotlin/ai/helper2/guide/usecase/AskGuide.kt` — dispatch by intent, clarification answer.
- `backend/src/main/kotlin/ai/helper2/guide/usecase/QuestionReader.kt` — plan, grounding, backup rules, window,
  `clarificationOf`, `askBackSubject`, about an event.
- `backend/src/main/kotlin/ai/helper2/guide/usecase/PickAnswer.kt`, `CompareAnswer.kt`, `ExplainAnswer.kt`,
  `DateAnswer.kt` — retrieval and answer per intent; `Question.kt` — step spans, searches, `askBack`, `namesakes`.
- `backend/src/main/kotlin/ai/helper2/guide/Intents.kt` — `GuideIntent`, `IntentRules` (with `namesEvent`),
  `Clarification`, `SubjectKind`, `AmbiguousSubject`, `isSubject`;
  `Guide.kt` — `GuideSearchPlan`, `GuideAnswer` blocks, type sets; `AnswerLimits.kt`; `GuideCost.kt` — `WINDOWED_TTL`.
- `backend/src/main/resources/i18n/intent-words_en.properties`, `intent-words_it.properties` — backup rule words and
  the event words (`when.event`).
- `backend/src/main/java/ai/helper2/guide/internal/SpringAiGuideModel.java` — plan, compare, explain calls.
- `backend/src/main/java/ai/helper2/guide/web/GuideStreamController.java` — `planned`, `understood`, `clarify` events.
- `backend/src/main/resources/prompts/guide/plan.txt`, `choose.txt`, `compare.txt`, `explain.txt`.
- `backend/src/main/kotlin/ai/helper2/search/Filtering.kt`, `Model.kt`, `usecase/SearchResources.kt` — window filter,
  `SourceQuery.publishedAfter`, `browse`, cache key.
- `backend/src/main/java/ai/helper2/sources/github/GitHubSource.java`, `sources/tavily/TavilySource.java`,
  `sources/npm/NpmSource.java`, `sources/tavily/CourseCollector.java`; `catalog/internal/ResourceStore.java` — date pushdown, npm dates, courses.
- `backend/src/main/resources/db/migration/V2__npm_dates_are_updates.sql`, `V3__course_type.sql`, `R__courses.sql`.
- `api/openapi.yaml` — request, response, status, stream events.
- `frontend/src/components/guide/GuideAnswer.tsx` — clarification chips, "Understood as", comparison table, explanation.

## Diagrams
[flow](../../diagrams/flow-0032-guide-intents.svg) · [sequence](../../diagrams/sequence-0032-guide-intents.svg)

## Roadmap
- Known limits: [roadmap § 10](../../roadmap.md#10-known-limits).
