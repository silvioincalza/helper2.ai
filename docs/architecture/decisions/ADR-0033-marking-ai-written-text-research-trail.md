# 0033 — Marking AI-written text, research trail

**Status:** Accepted

**Date:** 2026-11-14

**Depends on:** [0003](ADR-0003-contract-first-rest.md), [0008](ADR-0008-llm-integration.md), [0014](ADR-0014-evidence-gated-enrichment.md), [0023](ADR-0023-catalog-full-text-index.md), [0031](ADR-0031-guide-pipeline.md), [0032](ADR-0032-guide-cost-guard-and-answer-cache.md), [0034](ADR-0034-setup-guide.md), [0039](ADR-0039-frontend-one-box-state-in-the-url.md).

**Requirements:** NFR-010, NFR-013, C-005

## Context

The EU AI Act applies to Searchavo.ai because people in the EU use it. Searchavo.ai builds the guide on top of NVIDIA
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

The folded "How the guide decided" shows counts: candidates found, picks proposed, picks kept, suggestions removed.
A count does not let a user check the work. Two questions stay open:
- What did the guide actually search for? The planner rewrites the question, and a bad rewrite is the most common
  reason for a weak answer.
- What did the checks throw away, and why? "2 AI suggestions removed" says nothing about which ones.

Both must be safe to show. The queries are AI-written, so they are marked. A removed suggestion's reason is AI-written
text that failed a check: showing it would put unchecked model words on the page, which the gate exists to prevent.
And an answer reused from a similar question ([0032](ADR-0032-guide-cost-guard-and-answer-cache.md)) was planned from
another person's words.

## Decision

### The marker in the API
1. **One marker per guide answer.** `GuideResponse.aiContent` (`AiContentMarker`) is present when a model wrote text
   in the answer, and absent otherwise. It names the provider, the models, the AI-written fields this answer holds as
   JSON paths (`GuideDtoMapper.aiWrittenFields`, a subset of `AI_WRITTEN_FIELDS` in its order) and the page that
   explains the marking (`/mission`, a path, so any host works). A fallback answer's fixed reason ("Top search
   result") is ours, so `recommendations[].reason` is listed only when an AI pick has a reason; a question back that
   code wrote from the names of the results (an ambiguous subject, `Clarification.aiWritten` false) is not listed.
   The streamed `answer` event carries the same object.
2. **When a model wrote.** The answering call ran (`GuideAnswer.modelUsed`) or only the planner did
   (`GuideAnswer.planned`). When the planner read the question but the answering call then failed, the answer still
   holds the planner's search query and requirements: the marker is present and names the fast model only.
   `modelUsed` keeps its meaning for the cache and the "answered by" line.
3. **The AI-written fields** are `searchQuery`, `constraints[]`, `recommendations[].reason`,
   `recommendations[].checks[].constraint`, `clarification.question`, `clarification.options[]`,
   `comparison.rows[].aspect`, `comparison.rows[].cells[].text`, `explanation.sentences[].text`, `trail.queries[]`,
   `notices[].subject` and `notices[].query`. The OpenAPI description of each says it is AI-written.
4. **Every field is classed, by a test.** `GuideAiFieldsTest` walks the generated `GuideResponse` class and the
   streamed steps (`planned`, `understood`, `clarify`, `retrieved`, `chosen`, `validated`, `checked`). Every leaf field
   is either in `AI_WRITTEN_FIELDS` or in the test's list of fields no model writes, each group with its reason:
   - the source's own words: titles, links, descriptions, and quotes, which code keeps only when it finds them word
     for word in that text;
   - the asker's own words: `subjects[]` and `comparison.subjects[].name`. The planner reads them, but code keeps a
     subject only when the question contains it (`QuestionReader.groundedSubjects`);
   - our own words: `notices[].message`, `sources[].name`, `sources[].message`;
   - read by code: `intent` and `window`. The planner's intent and period count only when the question's own words
     support them, and code turns the period into dates;
   - set by code: codes, ids, numbers, dates and flags.

   A streamed step's field either repeats a classed field of the answer (its marking is the answer's, which always
   comes last) or is a model id or a count. A new field in the contract fails the test until someone classes it, and
   a classed field that no longer exists fails it too. So a field a rewritten prompt makes the model write cannot
   reach the API unmarked.
5. **Nothing AI-written hides in a field marked as ours.** A guide answer never carries the search notice
   `QUERY_EXPANDED` (word-list terms only, never the translation, [0023](ADR-0023-catalog-full-text-index.md)):
   the guide shows its own reading, `searchQuery`. A pick never carries a description an AI model wrote
   (`ValueOrigin.isAiWritten`: `AI_EXTRACTED`, `AI_SUMMARY`, `AI_ESTIMATED`; every origin states it and a test pins
   each one): `recommendations[].description` is the source's own text the quote was checked against, or null.
6. **Catalog values keep their origin.** `ResultCard.descriptionOrigin` and `Assertion.origin` say `AI_SUMMARY` or
   `AI_EXTRACTED`, and `AiSummary.model` names the model ([0014](ADR-0014-evidence-gated-enrichment.md)). The
   search's translation of a non-English query is its own field, `interpretation.translatedQuery`, AI-written by
   contract; `interpretation.expandedTerms` holds only the terms from the fixed word list.
7. **A search response and a resource carry the same marker.** `SearchResponse.aiContent` and
   `ResourceDetail.aiContent` are the `AiContentMarker` of the guide answer, built from what the response holds
   (`SearchController.aiContent`, `ResourceDetailController.aiContent`, `AiContentMarking.marker`); null when it
   holds no such value.
   - **A value from a model** has the origin `AI_EXTRACTED`, `AI_SUMMARY`, `AI_ESTIMATED` or `SELF_DECLARED`
     (`ValueOrigin.isFromAModel`): a model wrote it, or read it from a page. `isAiWritten` stays the narrower rule
     for descriptions (§5, §15).
   - **In a search response:** `interpretation.translatedQuery` when there is a translation; a result's
     `description` when its `descriptionOrigin` is from a model; and for an event whose `eventOrigin` is
     `AI_EXTRACTED` the values it holds of `timeline.eventStart.value`, `timeline.eventEnd.value`, `eventKind`,
     `eventFormat` and `location` (`CardMapper.aiWrittenFields`).
   - **In a resource:** the same card values under `card.`; `assertions[].value` of an assertion from a model;
     `relationships[].kind` and `relationships[].targetTitle` of a relationship from a model; and
     `summary.claims[].text`.
   - **The paths.** `results[].description` when every item of the array holds the value, else one path per item
     with its index, `results[2].description` (`AiContentMarking.inEachItem`).
   - **The models.** The fast model when there is a translation; the model of the enrichment run of each listed
     assertion and relationship (`ResourceDetail.Assertion.model`, `Relationship.model`, read with the run in
     `JdbcResourceDetailReads`) and of the summary. A card keeps no model for a stored description or for event days
     a model read, so these add none and `models` can be empty.
   - **Every field is classed, by a test.** `SearchAiFieldsTest` and `ResourceAiFieldsTest` walk the generated
     classes the way `GuideAiFieldsTest` does: each leaf field is AI-written (`SearchController.AI_WRITTEN_FIELDS`,
     `ResourceDetailController.AI_WRITTEN_FIELDS`) or in the test's list with its reason. The same tests give a
     response with no value from a model no marker, and each kind of value alone its marker.
   - **A code a model can choose is not text.** `ResultCard.type` (a model can choose the type of a repository of
     an awesome list, [0012](ADR-0012-scout-collectors-and-events.md)) and `Assertion.field` come from closed
     lists and are not listed.
8. **Other answers.** The setup guide names its
   model in `ToolGuide.model`, and when it shows steps, `ToolGuide.aiContent` names the step texts that are shown
   (a step whose text the step check could not confirm shows only its source and is not named) and the code
   block's language name as AI-written; `SetupAiFieldsTest` classes every field of the setup answers the way
   `GuideAiFieldsTest` does for the guide ([0034](ADR-0034-setup-guide.md)). The home examples pool names the label and
   query of an AI-drafted example in its `ai` marker ([0028](ADR-0028-home-examples.md)). In the back office, a
   review item with a drafted example carries its own `aiContent` (`draft.label`, `draft.query`, the draft's model),
   and the evidence view's `aiContent` names the run's AI-written values and `item.draft.label` and `item.draft.query`,
   also for an item with no run ([0038](ADR-0038-back-office-and-review.md) §7, §11).

### The marker in the page
9. **An attribute on every AI-written element.** `data-ai-generated="true"`, plus `data-ai-model` when the model is
   known (`frontend/src/lib/aiMark.ts`). The guide answer and the question back carry `data-ai-provider` and
   `data-ai-models`; so do, from their `aiContent` (`aiSource`), the "Also searched for" line with a translation and
   the details of a resource page (its summary, relationships and sources: not the guide's reason or the setup
   guide, which other models wrote), and the result list carries `data-ai-provider` when a result holds a value from a model.
   One helper writes both attributes (`aiSource`): `data-ai-models` names the models only when the marker knows them
   and is left out otherwise, and the result list names none (`listedDescriptionsSource` in `Results.tsx`), because the
   response's `models` hold the model of the query translation and no model is kept for a stored description.
   Marked: reasons, requirements (chips and checks), the question back and its options, comparison aspects and cells, explanation sentences, the planned search query (not our "Searched the catalog for:" around it)
   and the research trail's queries, AI summaries and values extracted by AI in the result list, the preview and on
   the resource page, the search's translation of the query in "Also searched for", the instructions of the setup
   guide's steps, and in the back office the AI values of a review item's evidence and a drafted example.
10. **The "not found" notice.** Its name and search (`GUIDE_SUBJECT_NOT_FOUND`) are the planner's, so they are their own
   fields (`notices[].subject`, `notices[].query`). The page puts them in our sentence ("X was not found in our
   sources; showing tools for: Y"), each in a marked element. The page never shows the `message` of these codes, so
   an older answer from the browser cache ([0039](ADR-0039-frontend-one-box-state-in-the-url.md)) says the sentence without the
   planner's words.
11. **Never marked.** Quotes, titles, links and the setup guide's commands: they come from the sources. Our own labels
   and the subjects (the asker's own words). The setup step's code language name is marked in the API only: the page
   shows it nowhere as text, only as `data-language` on the command's block, which holds the document's own text.
12. **A mark on screen for every marked group** (Art. 50(1)). The mark is a dot in the accent colour (`AiLabel`): a
    full dot ● for text a model wrote, a half dot ◐ for a value a model read from a source or from the question, a
    hollow dot ○ for a model's estimate. It is a button: pointing at it, focusing it or tapping it shows its words in
    a bubble over the page, above the mark or below it when there is no room, never cut off by its panel; screen
    readers read the words as its name. Inside another control (a "Try:" example) it is a plain mark whose words are
    part of that control's name; focusing the control shows its words, and on a touch screen they stand next to the
    dot. The words stay those of each group: "AI-written" on the picks bar, on the question back, on the reasons in
    the result list and the preview, on the planner's search terms, on the comparison and the explanation; "Read by
    AI" on the requirements (in the reading chips, over their checks in the preview and next to "Ask without" when
    nothing fits); "Name AI-written" or "Name and search AI-written" on the "not found" notice; "AI summary",
    "Extracted by AI", "AI-estimated" and "Self-declared by the vendor (extracted by AI)" on a description or a value
    (an AI-written description is headed "About", never "From the source", and is never shown as the proof of a pick);
    "Translated by AI" on the translation in "Also searched for", whose "not AI" note covers only the word-list terms.
    The setup guide keeps its note "Steps written by AI" as a sentence. A key "AI marks: ● written by AI ◐ read from
    the source by AI ○ estimated by AI, can be wrong" sits in the footer of every page and on the Sources page. An AI
    mark never shows without its text: a reason the check removed shows neither.
13. **Explained in public.** The Mission page has a section "How we mark AI text".
14. **No hidden characters in the text.** We add no zero-width or other invisible characters.

### Crawler pages
15. **AI text never reaches the head of a page.** The prerendered resource pages put only text people wrote in the
    meta description, Open Graph and JSON-LD: a description whose origin is `SOURCE_PROVIDED`, `CURATED` or
    `COMMUNITY` (`PageHead.WRITTEN_BY_PEOPLE`). The facts come from the resource's own columns, not from AI
    assertions.

### The research trail
16. **In the contract.** `GuideResponse.trail` (`GuideTrail`):
    - `queries`: the planner's search queries a search really sent, in order. At most 3, each at most 200
      characters, trimmed, without repeats, none with a link (`LinkText.containsLink`).
    - `removed`: one item per suggestion a check removed, in the order proposed: a pick, a comparison cell or an
      explanation sentence. Each item has the `title` of the source it quoted (from that source, at most 200
      characters) and a `check` code. At most 12.

    The trail never holds the removed AI reason or raw model output. The domain type (`ResearchTrail`) has no field
    for them.
17. **Which queries.** A query is listed only when the planner wrote it and a search sent it:
    - the planned query (`searchQuery`), or a query cut from it (NEWEST without a subject, WHEN without a subject);
    - the planned web query, when a source that reads it (`usesWebQuery`) was asked and not skipped.

    Not listed: a subject's name (comparison, explanation, alternatives, dates), which is the asker's own words; the
    subject's use cases that ALTERNATIVES searches with, which are the source's words; any query when the planner did
    not run, failed or wrote a blank query or one with a link, because then the searches ran with the asker's text;
    a web query with a link, which is dropped before the sources read it. `GuideSteps.find`
    records them on the `Question` (`Question.ran`). The comparison runs its searches at the same time, so the record
    is synchronised.
18. **Which removals, and their code.** The validator keeps each rejected suggestion that named a real candidate
    (`GuideValidator.Result.rejectedPicks`). Its rejection maps to one code (`Rejection.trailCheck`):

    | Rejection | Code |
    |---|---|
    | `QUOTE_NOT_IN_TEXT`, `QUOTE_TOO_SHORT`, `QUOTE_TOO_LONG` | `QUOTE_NOT_FOUND` |
    | `NO_REASON`, `REASON_TOO_LONG`, `REASON_INVALID` | `REASON_NOT_SUPPORTED` |
    | `READING_TYPE`, `OUTSIDE_WINDOW` | `CONSTRAINT_NOT_MET` |
    | `OFF_NEED` (the reason check says the pick does not fit the need, [0031](ADR-0031-guide-pipeline.md)) | `OFF_NEED` |
    | `UNKNOWN_CANDIDATE`, `DUPLICATE`, `OVER_LIMIT` | not listed: no candidate, or no check failed |

    - A candidate rejected once and kept on a second proposal is a pick, not a removal.
    - A pick whose reason the reason check removes ([0031](ADR-0031-guide-pipeline.md)) stays a pick: the page already
      says its explanation was removed. It is not listed, so no title shows both as a pick and as removed.
    - A comparison cell and an explanation sentence are listed too, by the same codes: text empty, too long or with a
      link → `REASON_NOT_SUPPORTED`; quote not in the source → `QUOTE_NOT_FOUND`; removed by the reason check →
      `REASON_NOT_SUPPORTED` (they are dropped, not kept without text like a pick). The line names the compared
      subject or the quoted source, never the cell, its aspect or the sentence: those are AI-written and failed a
      check. The subject's or the source's card can still show: what was removed is one claim about it, not the
      resource.
    - A comparison row dropped for its aspect alone names no source and is not listed.

    `rejected` keeps counting every rejection, so it can be larger than `removed`.
19. **Reused answers.** An answer reused from a similar question carries no `queries` (`ResearchTrail.withoutQueries`,
    in `AskGuide.similarAnswer`), and its `searchQuery` is empty: the planner wrote both from another asker's words.
    The same goes for the search in a "not found" notice (`notices[].query`); its name stays, as it is part of how
    both questions were read. The copy stored under the new question's key has none of them, so a later exact hit
    shows none. The removed titles stay: they come from the sources. An exact-key hit keeps its queries: its question
    has the same normalised text.
20. **Marking.** `trail.queries[]` is in `AI_WRITTEN_FIELDS`. On the page each query has `data-ai-generated` and
    `data-ai-model` (the fast model) and the row has the "AI-written" label. `trail.removed[]` is not marked: the
    title comes from the source and the check is our own text. A removed AI reason is never shown, in the trail or
    anywhere else; only its code is.
21. **On the page.** Inside the folded "How the guide decided" (`GuidePicks.FullAnswer` → `GuideAnswer`), folded by
    default:
    - "Planned": "Searched for:" and one line per query, AI-marked and labelled. With no query: "No AI-written search
      terms to show". The answer's own `searchQuery` is not shown in its place.
    - "Checked": one line per removed item, "title: check". With nothing removed and nothing rejected: "None removed".
    - React renders all text as text. A title or query with HTML in it shows the HTML as characters.
22. **Cache and privacy.** The cache `SHAPE` is `v3` and is part of the cache key, so every answer served from the
    cache has a trail. The Privacy page says that a cached guide answer, with the search queries the guide wrote for
    it, can be shown to anyone who asks the same or a very similar question.

### Code

- `api/common.yaml`, `api/public.yaml`: `AiContentMarker`, `GuideResponse.aiContent`, `SearchResponse.aiContent`,
  `ResourceDetail.aiContent`, `GuideTrail`,
  `GuideTrailRemoved`, `QueryInterpretation.translatedQuery`, `Notice.subject` and `Notice.query`.
- `backend/apps/public/src/main/java/ai/searchavo/guide/web/GuideDtoMapper.java`: `AI_WRITTEN_FIELDS`, the marker,
  `notices` (no `QUERY_EXPANDED`), `sourceDescription`, `trail`.
- `backend/apps/public/src/main/kotlin/ai/searchavo/guide/steps/QuestionReader.kt`: `groundedSubjects`, the intent and
  period read by code.
- `backend/apps/public/src/main/kotlin/ai/searchavo/guide/Guide.kt`: `ResearchTrail`, `RemovedSuggestion`, `TrailCheck`,
  `GuideValidator.Rejection.trailCheck`, `GuideValidator.removedOf`.
- `backend/apps/public/src/main/kotlin/ai/searchavo/guide/steps/Question.kt`: `Question.ran`, `GuideSteps.find`.
- `backend/apps/public/src/main/kotlin/ai/searchavo/guide/steps/PickAnswer.kt`, `CompareAnswer.kt`, `ExplainAnswer.kt`;
  `usecase/AskGuide.kt` (`answerByIntent`, `similarAnswer`).
- `backend/apps/public/src/main/kotlin/ai/searchavo/guide/GuideCost.kt`: `SHAPE`.
- `backend/libs/core/src/main/java/ai/searchavo/search/web/SearchController.java`: `translatedQuery` apart from
  the word-list terms (`AnalyzedQuery.translation`, `lexiconTerms`); `aiContent`, `AI_WRITTEN_FIELDS`.
  `CardMapper.java`: `aiWrittenFields`.
- `backend/libs/core/src/main/java/ai/searchavo/api/AiContentMarking.java`: `marker`, `inEachItem`, `POLICY`.
- `backend/apps/public/src/main/java/ai/searchavo/catalog/web/ResourceDetailController.java`: `aiContent`,
  `AI_WRITTEN_FIELDS`. `backend/libs/core/src/main/java/ai/searchavo/catalog/internal/JdbcResourceDetailReads.java`: the
  run's model of an assertion and a relationship.
- `backend/libs/kernel/src/main/kotlin/ai/searchavo/shared/Vocabulary.kt`: `ValueOrigin.isAiWritten`, `isFromAModel`.
- `backend/apps/public/src/main/kotlin/ai/searchavo/site/pages/PageHead.kt`: `WRITTEN_BY_PEOPLE`.
- `frontend/src/lib/aiMark.ts`: the AI origins (`AI_ORIGIN`, `isAiOrigin`) and the page attributes (`aiMark`, `aiMarkWhen`, `aiModel`,
  `aiSource`): no other file writes a `data-ai-*` attribute. Used in `GuideAnswer.tsx`, `Clarify.tsx`, `AnswerBlocks.tsx`,
  `GuidePicks.tsx`, `Understood.tsx`, `Results.tsx`, `ResultRow.tsx`, `Resource.tsx`, `ResultsTimeline.tsx`,
  `FieldStates.tsx`, `ResourcePage.tsx`, `SetupGuide.tsx`. `GuideAnswer.tsx` also has `PlannedQueries` and
  `RemovedList`.
- Tests: `GuideAiFieldsTest`, `SearchAiFieldsTest`, `ResourceAiFieldsTest`, `ValueOriginTest`, `GuideDtoMapperTest`, `AskGuideTrailTest`, `AskGuideIntentsTest`, `AskGuideSimilarTest`,
  `SharedGuideAnswerStoreTest`, `GuideTrail.test.tsx`, `GuideIntents.test.tsx`.

## Alternatives considered

- **Invisible Unicode watermark in the text**: breaks copy and paste, search and screen readers, and anyone can
  strip it.
- **Signed provenance manifest per answer** (C2PA-style): the strongest proof of origin, but no common tooling for
  text yet, and it needs key management. Open ([#284](https://github.com/silvioincalza/searchavo.ai/issues/284)).
- **Only the visible labels**: meets Art. 50(1), not Art. 50(2).
- **Classing fields by hand, without a test**: a renamed or new field would slip through a prompt rewrite.
- **Mark the subjects as AI-written**: they are the asker's words, kept only when the question contains them.
- **Show the removed reason, greyed out**: it is model text that failed the check; showing it undoes the gate.
- **List every rejection, with its raw code**: duplicates and "no room" are not failed checks, and the raw names mean
  nothing to a user.
- **Strip the trail from reused answers entirely**: the removed titles come from the sources and are safe to share.
- **Keep the queries on a reused answer, marked as another person's**: they are still someone else's words.

## Consequences

- A crawler, a browser extension or a regulator can find AI-written text in the page and in the guide's API without
  guessing.
- The text itself stays clean: copy and paste, search and screen readers work as for any other text. The mark is
  metadata, so it is lost when the text is copied.
- A new field in the guide's contract fails `GuideAiFieldsTest` until it is classed. A field classed AI-written still
  needs its `aiMark` element and its label in the UI by hand; the frontend tests check the components that exist.
- A user can see what the guide searched for and what its checks removed, and check both against the sources. The
  page shows no model words that failed a check.
- A reused answer leaks neither the other asker's queries nor their planned search query, on the page or in the API.
- The trail adds no model call and no search. It adds two short lists to each answer and to each cached entry.
- A client finds the AI-written values of a search response and of a resource in one place, `aiContent.fields`,
  without knowing which origins mean a model. The per-value origins (`descriptionOrigin`, `Assertion.origin`,
  `eventOrigin`, `AiSummary.model`) stay, and say the same.
- A new field in `SearchResponse` or `ResourceDetail` fails `SearchAiFieldsTest` or `ResourceAiFieldsTest` until it
  is classed.
- Reading a resource also reads the model of the enrichment run of each assertion and relationship, in the same
  query.
- The streamed `planned` step carries its own `aiContent` (the fast model; `searchQuery` only when the planner wrote
  the query, else the field is empty and the question's own words are searched; `constraints[]` when it read some;
  no marker when it wrote neither),
  and the `clarify` step (`GuideStreamClarify`) carries one (fields `question`, `options[]`) when the planner wrote
  the question back. A client that shows a step before the `answer` event still has the machine-readable mark.

### STRIDE

| Threat | Where | Mitigation |
|---|---|---|
| Tampering | a model writes a new field that reaches the page unmarked | `GuideAiFieldsTest`, `SearchAiFieldsTest` and `ResourceAiFieldsTest` fail on an unclassed field |
| Tampering | model words hidden in a field marked as ours (a translation in a notice, an AI description on a pick) | `QUERY_EXPANDED` lists word-list terms only; the mapper drops it and AI-origin descriptions |
| Information disclosure | a reused answer shows another asker's queries | `withoutQueries`, empty `searchQuery`, no notice query |
| Spoofing | a removed reason shown as if checked | the trail holds only a title and a code |
| Tampering | HTML in a title or query | React renders text, never HTML |

### AI architecture check

- 9 Guardrails: covered. Quote and evidence gates by code; the trail shows codes, never failed model text.
- 10 Observability: covered. `aiContent` names the models per answer; the trail names the queries run.
- 11 Evaluation: covered for the contract (`GuideAiFieldsTest`, mapper and frontend tests). The eval scripts read
  every AI-written text the answer shows.
- The other layers: not touched by this record.

### Legal points
Tracked privately (S9.6.3).

### Known limits

- The research trail lists no duplicate and no pick dropped for lack of room, so `rejected` can be larger
  than the list. A reused answer shows no queries and no search query.
- Metadata marking is lost when text is copied. A new field in the guide's or the setup
  guide's contract fails `GuideAiFieldsTest` or `SetupAiFieldsTest` until it is classed as AI-written or not; its
  `aiMark` element and label in the UI are still added by hand.
- `aiContent.models` of a search response or a resource can be empty: a card keeps no model for a stored AI
  description or for event days a model read. The translation is cached
  ([0023](ADR-0023-catalog-full-text-index.md)), so after a change of the fast model a cached translation is
  named with the new model until the cache entry ends.
- `RecentResponse` and `UpcomingEventsResponse` carry cards and no `aiContent`: the per-value `eventOrigin` and
  `descriptionOrigin` mark their AI values, so a client must read the contract.
- A source's own assertion for `description` is `SOURCE_PROVIDED` or `CURATED` (`GetResourceDetail.originOf`), also
  when the description's origin is from a model. No collector writes such a description today.

## Diagrams

- Marking: [flow](../diagrams/flow-0033-ai-content-marking.svg) · [sequence](../diagrams/sequence-0033-ai-content-marking.svg)
- Research trail: [flow](../diagrams/flow-0033-research-trail.svg) · [sequence](../diagrams/sequence-0033-research-trail.svg)

## Backlog

- Features: [F4.3](https://github.com/silvioincalza/searchavo.ai/issues/66) AI-written text marked (Art. 50) and research trail.
- Open issues that change this decision:
  - [#273](https://github.com/silvioincalza/searchavo.ai/issues/273) S4.3.5 Tracked privately (legal)
  - [#284](https://github.com/silvioincalza/searchavo.ai/issues/284) S4.8.1 Each AI answer signed with its model, time and AI-written fields
