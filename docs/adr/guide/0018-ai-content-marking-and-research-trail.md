# 0018 — Marking AI-written text and the research trail

**Status.** Accepted. Merges old 0038, 0044 (texts merged in PR 6 of the reset). Until then each part below is the old record as it was, with its numbers and links moved to the new ones.

<a id="part-0038"></a>

## Part: was ADR 0038 — Marking AI-written text: labels on screen, metadata in the API and the page

**Status.** Accepted. **Builds on:** [0003](../foundation/0003-contract-first-rest-api.md), [0008](../foundation/0008-llm-integration.md), [0016](0016-guide-pipeline.md), [0023](../scout/0023-evidence-gated-enrichment.md)

### Context
The EU AI Act applies to Helper2.ai because people in the EU use it. Helper2.ai builds the guide on top of NVIDIA
Nemotron and offers it under its own name, so it is most likely the provider of an AI system that writes text.
Art. 50 has applied since 2 August 2026:
- Art. 50(1): people must know they deal with AI. The UI says so ("Ask the AI guide", "AI reasoning",
  "AI summary", "Extracted by AI").
- Art. 50(2): AI-written text must also be marked in a machine-readable way, so it can be detected, as far as
  technically feasible.

The Commission's Code of Practice on marking AI content (June 2026) is voluntary. It asks for marking, two layers
where needed (metadata, watermark or similar), and a way to detect it.

We call Nemotron through Nebius Token Factory. We do not control sampling, so we cannot add a statistical watermark
to the text.

### Decision
1. **API: one marker per guide answer.** `GuideResponse.aiContent` (`AiContentMarker`) is present when a model wrote
   text in the answer, and absent otherwise. It names the provider, the models, the AI-written fields as JSON paths
   (`GuideDtoMapper.AI_WRITTEN_FIELDS`) and the page that explains the marking (`/mission`, a path, so any host
   works). The streamed `answer` event carries the same object.
   "A model wrote text" means the answering call ran (`GuideAnswer.modelUsed`) or only the planner did
   (`GuideAnswer.planned`). When the planner read the question but the answering call then failed, the answer still
   holds the planner's search query and requirements: the marker is present and names the fast model only.
   `modelUsed` keeps its meaning for the cache and the "answered by" line.
2. **API: catalog values keep their origin.** `ResultCard.descriptionOrigin` and `Assertion.origin` say
   `AI_SUMMARY` or `AI_EXTRACTED`, and `AiSummary.model` names the model. The search's translation of a
   non-English query ([0010](../search/0010-catalog-full-text-index.md)) is its own field,
   `interpretation.translatedQuery`, AI-written by contract; `interpretation.expandedTerms` holds only the terms from
   the fixed word list.
3. **Page: an attribute on every AI-written element.** `data-ai-generated="true"`, plus `data-ai-model` when the
   model is known (`src/lib/aiMark.ts`). The guide answer and the question back carry `data-ai-provider` and
   `data-ai-models`. Marked: reasons, requirements (chips and checks), the question back and its options, comparison
   aspects and cells, explanation sentences, the prompt, the planned search query (not our "Searched the catalog
   for:" around it) and the research trail's queries ([the part that was ADR 0044](#part-0044)), AI summaries and values extracted
   by AI in the result list, the preview and on the resource page, the search's translation of the query in "Also
   searched for", and the instructions of the setup guide's steps ([0019](0019-setup-guide.md);
   `ToolGuide.model` names the model in the API). Also the name and the search in the "not found" notice
   (`GUIDE_SUBJECT_NOT_FOUND`): the planner picked them, so they are their own fields (`notices[].subject`,
   `notices[].query`), and the page puts them in our sentence ("X was not found in our sources; showing tools for:
   Y"), each in a marked element. A notice's `message` is our own words (except `QUERY_EXPANDED`, whose terms can hold the model translation): the page never shows it for these
   codes, so an older answer from the browser cache ([0027](../frontend/0027-frontend-one-box.md)) says the sentence without the
   planner's words. Quotes, titles, links and the setup guide's commands are never
   marked: they come from the sources. Our own labels and the subjects (the user's own words) are not marked.
   Every marked group also has a label on screen (Art. 50(1)): "AI-written" on the picks bar, on the question back,
   on the reasons in the result list and the preview, on the requirements ("Read by AI", in the reading chips, over their
   checks in the preview and next to "Ask without" when nothing fits), on the planner's search terms, on the
   comparison, the explanation and the prompt, and "Name AI-written" or "Name and search AI-written" on the "not found"
   notice; "AI summary" and "Extracted by AI" on a description or a value (an AI-written description is headed
   "About", never "From the source", and is never shown as the proof of a pick); "Translated by AI" on the
   translation in "Also searched for", whose "not AI" note covers only the word-list terms; "Steps written by AI" in
   the setup guide. An AI-written label never shows without its text: a reason the check removed shows neither.
4. **Explained in public.** The Mission page has a section "How we mark AI text".
5. **No hidden characters in the text.** We do not add zero-width or other invisible characters.

### Consequences
- A crawler, a browser extension or a regulator can find AI-written text in the page and in the API without guessing.
- The text itself stays clean: copy and paste, search and screen readers work as for any other text.
- A new AI-written field goes into `AI_WRITTEN_FIELDS` and is marked in the UI.
- The setup guide ([0019](0019-setup-guide.md)): the model writes each step's instruction, so the instruction is
  labelled and marked; the quotes and commands it shows are copied from the tool's own documents and are not.

### Alternatives considered
- **Invisible Unicode watermark in the text** — breaks copy and paste, search and screen readers, and anyone can
  strip it.
- **Signed provenance manifest per answer** (C2PA-style) — the strongest proof of origin, but no common tooling
  for text yet, and it needs key management. On the roadmap.
- **Only the visible labels** — meets Art. 50(1), not Art. 50(2).

### Code
- `api/common.yaml, api/public.yaml` — `AiContentMarker`, `GuideResponse.aiContent`, `QueryInterpretation.translatedQuery`.
- `backend/.../guide/web/GuideDtoMapper.java` — builds the marker.
- `backend/.../search/web/SearchController.java` — `translatedQuery` apart from the word-list terms
  (`AnalyzedQuery.translation`, `lexiconTerms`).
- `frontend/src/lib/aiMark.ts` — the page attributes; used in `GuideAnswer.tsx`, `Clarify.tsx`, `Results.tsx`,
  `Resource.tsx`, `ResultsTimeline.tsx`, `ResourcePage.tsx`.

### Diagrams
[flow](../../diagrams/flow-0018-ai-content-marking.svg) · [sequence](../../diagrams/sequence-0018-ai-content-marking.svg)

### Roadmap
- [Signed provenance manifest for AI-written text](../../roadmap.md#4-ai-quality-and-cost)
- Known limits: [roadmap § 9](../../roadmap.md#9-known-limits).

<a id="part-0044"></a>

## Part: was ADR 0044 — Research trail: the queries the guide ran and the suggestions its checks removed

**Status.** Accepted. **Builds on:** [the part that was ADR 0038](#part-0038), [0016](0016-guide-pipeline.md), [0017](0017-guide-cost-guard-and-answer-cache.md)

### Context
The folded "How the guide decided" shows counts: candidates found, picks proposed, picks kept, suggestions removed.
A count does not let a user check the work. Two questions stay open:
- What did the guide actually search for? The planner rewrites the question, and a bad rewrite is the most common
  reason for a weak answer.
- What did the checks throw away, and why? "2 AI suggestions removed" says nothing about which ones.

Both must be safe to show:
- The queries are AI-written, so they are marked like the planned search query (the part that was ADR 0038).
- A removed suggestion's reason is AI-written text that failed a check. Showing it would put unchecked model words on
  the page, which the gate exists to prevent.
- An answer reused from a similar question (ADR 0017) was planned from another person's words.

### Decision

#### 1. The trail in the contract
`GuideResponse.trail` (`GuideTrail`):
- `queries`: the planner's search queries a search really sent, in order. At most 3, each at most 200 characters,
  trimmed, without repeats.
- `removed`: one item per suggestion a check removed, in the order proposed: a pick, a comparison cell or an
  explanation sentence. Each item has the `title` of the source it quoted (from that source, at most 200
  characters) and a `check` code. At most 12.

The trail never holds the removed AI reason, raw model output or the prompt. The domain type (`ResearchTrail`) has no
field for them.

#### 2. Which queries
A query is listed only when the planner wrote it and a search sent it:
- the planned query (`searchQuery`), or a query cut from it (NEWEST without a subject, WHEN without a subject);
- the planned web query, when a source that reads it (`usesWebQuery`) was asked and not skipped.

Not listed:
- a subject's name (comparison, explanation, alternatives, dates): the asker's own words;
- the subject's use cases that ALTERNATIVES searches with: the source's words;
- any query when the planner did not run, failed or wrote a blank query: the searches ran with the asker's text.

`GuideSteps.find` records them on the `Question`. The comparison runs its searches at the same time, so the record is
synchronised.

#### 3. Which removals, and their code
The validator keeps each rejected suggestion that named a real candidate (`GuideValidator.Result.rejectedPicks`).
Its rejection maps to one code (`Rejection.trailCheck`):

| Rejection | Code |
|---|---|
| `QUOTE_NOT_IN_TEXT`, `QUOTE_TOO_SHORT`, `QUOTE_TOO_LONG` | `QUOTE_NOT_FOUND` |
| `NO_REASON`, `REASON_TOO_LONG`, `REASON_INVALID` | `REASON_NOT_SUPPORTED` |
| `READING_TYPE`, `OUTSIDE_WINDOW` | `CONSTRAINT_NOT_MET` |
| `UNKNOWN_CANDIDATE`, `DUPLICATE`, `OVER_LIMIT`, `PROMPT_INVALID` | not listed: no candidate, or no check failed |

- A candidate rejected once and kept on a second proposal is a pick, not a removal.
- A pick whose reason the second model call removes (ADR 0016) stays a pick: the page already says its explanation
  was removed. It is not listed, so no title shows both as a pick and as removed.
- A comparison cell and an explanation sentence are listed too, by the same codes: text empty, too long or with a
  link → `REASON_NOT_SUPPORTED`; quote not in the source → `QUOTE_NOT_FOUND`; removed by the reason check →
  `REASON_NOT_SUPPORTED` (they are dropped, not kept without text like a pick). The line names the compared subject
  or the quoted source, never the cell, its aspect or the sentence: those are AI-written and failed a check. The
  subject's or the source's card can still show: what was removed is one claim about it, not the resource.
- A comparison row dropped for its aspect alone names no source and is not listed.

`rejected` keeps counting every rejection, so it can be larger than `removed`.

#### 4. Reused answers
An answer reused from a similar question carries no `queries` (`ResearchTrail.withoutQueries`, in
`AskGuide.similarAnswer`), and its `searchQuery` is empty: the planner wrote both from another asker's words. The same
goes for the search in a "not found" notice (`notices[].query`, [the part that was ADR 0038](#part-0038)); its name stays, as
it is part of how both questions were read. The copy stored under the new question's key has none of them, so a later
exact hit shows none. The removed titles stay: they come from the sources. An exact-key hit keeps its queries: its question has the
same normalised text.

#### 5. Marking
- `trail.queries[]` is in `AI_WRITTEN_FIELDS`. On the page each query has `data-ai-generated` and `data-ai-model`
  (the fast model) and the row has the "AI-written" label.
- `trail.removed[]` is not marked: the title comes from the source and the check is our own text.
- A removed AI reason is never shown, in the trail or anywhere else. Only its code is. This keeps the part that was ADR 0038's rule: an
  AI-written label never shows without its text, and a reason the check removed shows neither.

#### 6. On the page
Inside the folded "How the guide decided" (`GuidePicks.FullAnswer` → `GuideAnswer`), folded by default:
- "Planned": "Searched for:" and one line per query, AI-marked and labelled. With no query: "No AI-written search
  terms to show". The answer's own `searchQuery` is not shown in its place.
- "Checked": one line per removed item, "title: check". With nothing removed and nothing rejected: "None removed".
- All text is rendered as text by React. A title or query with HTML in it shows the HTML as characters.

#### 7. Cache and privacy
- The cache `SHAPE` is `v3` and is part of the cache key, so every answer served from the cache has a trail.
- Privacy page: a cached guide answer, with the search queries the guide wrote for it, can be shown to anyone who
  asks the same or a very similar question.

### Consequences
- A user can see what the guide searched for and what its checks removed, and check both against the sources.
- The page shows no model words that failed a check.
- A reused answer leaks neither the other asker's queries nor their planned search query, on the page or in the API.
- The trail adds no model call and no search. It adds two short lists to each answer and to each cached entry.
- A lawyer must confirm: whether AI-written search queries fall under Art. 50(2) (we mark them anyway), and whether
  the privacy text covers showing a cached answer built from someone else's question.

### Alternatives considered
- **Show the removed reason, greyed out** — it is model text that failed the check; showing it undoes the gate.
- **List every rejection, with its raw code** — duplicates and "no room" are not failed checks, and the raw names mean
  nothing to a user.
- **Strip the trail from reused answers entirely** — the removed titles come from the sources and are safe to share.
- **Keep the queries on a reused answer, marked as another person's** — they are still someone else's words.

### Code
- `api/public.yaml` — `GuideTrail`, `GuideTrailRemoved`, `GuideResponse.trail`.
- `backend/apps/public/src/main/kotlin/ai/helper2/guide/Guide.kt` — `ResearchTrail`, `RemovedSuggestion`, `TrailCheck`,
  `GuideValidator.Rejection.trailCheck`, `GuideValidator.removedOf`.
- `backend/apps/public/src/main/kotlin/ai/helper2/guide/steps/Question.kt` — `Question.ran`, `GuideSteps.find`.
- `backend/apps/public/src/main/kotlin/ai/helper2/guide/steps/PickAnswer.kt`, `CompareAnswer.kt`, `ExplainAnswer.kt`,
  `AskGuide.kt` (`answerByIntent`, `similarAnswer`).
- `backend/apps/public/src/main/kotlin/ai/helper2/guide/GuideCost.kt` — `SHAPE`.
- `backend/apps/public/src/main/java/ai/helper2/guide/web/GuideDtoMapper.java` — `trail`, `AI_WRITTEN_FIELDS`.
- `frontend/src/components/guide/GuideAnswer.tsx` — `PlannedQueries`, `RemovedList`.
- Tests: `AskGuideTrailTest`, `AskGuideIntentsTest`, `AskGuideSimilarTest`, `GuideDtoMapperTest`, `SharedGuideAnswerStoreTest`,
  `GuideTrail.test.tsx`.

### Diagrams
[flow](../../diagrams/flow-0018-research-trail.svg) · [sequence](../../diagrams/sequence-0018-research-trail.svg)

### Roadmap
- Known limits: [roadmap § 9](../../roadmap.md#9-known-limits).
