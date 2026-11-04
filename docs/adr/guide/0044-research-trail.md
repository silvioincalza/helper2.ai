# 0044 — Research trail: the queries the guide ran and the suggestions its checks removed

**Status.** Accepted. **Amends:** [0038](0038-ai-content-marking.md). **Builds on:** [0015](0015-ai-guide-evidence-gate.md), [0016](0016-guide-reason-check.md), [0041](0041-guide-answer-cache.md)

## Context
The folded "How the guide decided" shows counts: candidates found, picks proposed, picks kept, suggestions removed.
A count does not let a user check the work. Two questions stay open:
- What did the guide actually search for? The planner rewrites the question, and a bad rewrite is the most common
  reason for a weak answer.
- What did the checks throw away, and why? "2 AI suggestions removed" says nothing about which ones.

Both must be safe to show:
- The queries are AI-written, so they are marked like the planned search query (ADR 0038).
- A removed suggestion's reason is AI-written text that failed a check. Showing it would put unchecked model words on
  the page, which the gate exists to prevent.
- An answer reused from a similar question (ADR 0041) was planned from another person's words.

## Decision

### 1. The trail in the contract
`GuideResponse.trail` (`GuideTrail`):
- `queries`: the planner's search queries a search really sent, in order. At most 3, each at most 200 characters,
  trimmed, without repeats.
- `removed`: one item per suggestion a check removed, in the order proposed: a pick, a comparison cell or an
  explanation sentence. Each item has the `title` of the source it quoted (from that source, at most 200
  characters) and a `check` code. At most 12.

The trail never holds the removed AI reason, raw model output or the prompt. The domain type (`ResearchTrail`) has no
field for them.

### 2. Which queries
A query is listed only when the planner wrote it and a search sent it:
- the planned query (`searchQuery`), or a query cut from it (NEWEST without a subject, WHEN without a subject);
- the planned web query, when a source that reads it (`usesWebQuery`) was asked and not skipped.

Not listed:
- a subject's name (comparison, explanation, alternatives, dates): the asker's own words;
- the subject's use cases that ALTERNATIVES searches with: the source's words;
- any query when the planner did not run, failed or wrote a blank query: the searches ran with the asker's text.

`GuideSteps.find` records them on the `Question`. The comparison runs its searches at the same time, so the record is
synchronised.

### 3. Which removals, and their code
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

### 4. Reused answers
An answer reused from a similar question carries no `queries` (`ResearchTrail.withoutQueries`, in
`AskGuide.similarAnswer`), and its `searchQuery` is empty: the planner wrote both from another asker's words. The copy
stored under the new question's key has neither, so a later exact hit shows neither. The removed titles stay: they come from the sources. An exact-key hit keeps its queries: its question has the
same normalised text.

### 5. Marking (amends 0038)
- `trail.queries[]` is in `AI_WRITTEN_FIELDS`. On the page each query has `data-ai-generated` and `data-ai-model`
  (the fast model) and the row has the "AI-written" label.
- `trail.removed[]` is not marked: the title comes from the source and the check is our own text.
- A removed AI reason is never shown, in the trail or anywhere else. Only its code is. This keeps 0038's rule: an
  AI-written label never shows without its text, and a reason the check removed shows neither.

### 6. On the page
Inside the folded "How the guide decided" (`GuidePicks.FullAnswer` → `GuideAnswer`), folded by default:
- "Planned": "Searched for:" and one line per query, AI-marked and labelled. With no query: "No AI-written search
  terms to show". The answer's own `searchQuery` is not shown in its place.
- "Checked": one line per removed item, "title: check". With nothing removed and nothing rejected: "None removed".
- All text is rendered as text by React. A title or query with HTML in it shows the HTML as characters.

### 7. Cache and privacy
- The cache `SHAPE` is `v3`: an answer cached in the old shape has no trail and is not served after the deploy.
- Privacy page: a cached guide answer, with the search queries the guide wrote for it, can be shown to anyone who
  asks the same or a very similar question.

## Consequences
- A user can see what the guide searched for and what its checks removed, and check both against the sources.
- The page shows no model words that failed a check.
- A reused answer leaks neither the other asker's queries nor their planned search query, on the page or in the API.
- The trail adds no model call and no search. It adds two short lists to each answer and to each cached entry.
- A lawyer must confirm: whether AI-written search queries fall under Art. 50(2) (we mark them anyway), and whether
  the privacy text covers showing a cached answer built from someone else's question.

## Alternatives considered
- **Show the removed reason, greyed out** — it is model text that failed the check; showing it undoes the gate.
- **List every rejection, with its raw code** — duplicates and "no room" are not failed checks, and the raw names mean
  nothing to a user.
- **Strip the trail from reused answers entirely** — the removed titles come from the sources and are safe to share.
- **Keep the queries on a reused answer, marked as another person's** — they are still someone else's words.

## Code
- `api/openapi.yaml` — `GuideTrail`, `GuideTrailRemoved`, `GuideResponse.trail`.
- `backend/src/main/kotlin/ai/helper2/guide/Guide.kt` — `ResearchTrail`, `RemovedSuggestion`, `TrailCheck`,
  `GuideValidator.Rejection.trailCheck`, `GuideValidator.removedOf`.
- `backend/src/main/kotlin/ai/helper2/guide/usecase/Question.kt` — `Question.ran`, `GuideSteps.find`.
- `backend/src/main/kotlin/ai/helper2/guide/usecase/PickAnswer.kt`, `CompareAnswer.kt`, `ExplainAnswer.kt`,
  `AskGuide.kt` (`answerByIntent`, `similarAnswer`).
- `backend/src/main/kotlin/ai/helper2/guide/GuideCost.kt` — `SHAPE`.
- `backend/src/main/java/ai/helper2/guide/web/GuideDtoMapper.java` — `trail`, `AI_WRITTEN_FIELDS`.
- `frontend/src/components/guide/GuideAnswer.tsx` — `PlannedQueries`, `RemovedList`.
- Tests: `AskGuideTrailTest`, `AskGuideIntentsTest`, `AskGuideSimilarTest`, `GuideDtoMapperTest`, `SharedGuideAnswerStoreTest`,
  `GuideTrail.test.tsx`.

## Diagrams
[flow](../../diagrams/flow-0044-research-trail.svg) · [sequence](../../diagrams/sequence-0044-research-trail.svg)

## Roadmap
- Known limits: [roadmap § 9](../../roadmap.md#9-known-limits).
