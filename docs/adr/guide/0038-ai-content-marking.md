# 0038 — Marking AI-written text: labels on screen, metadata in the API and the page

**Status.** Accepted. **Builds on:** [0003](../foundation/0003-contract-first-rest-api.md), [0014](0014-llm-integration.md), [0015](0015-ai-guide-evidence-gate.md), [0019](../scout/0019-evidence-gated-enrichment.md)

## Context
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

## Decision
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
   non-English query ([0011](../search/0011-language-aware-search.md)) is its own field,
   `interpretation.translatedQuery`, AI-written by contract; `interpretation.expandedTerms` holds only the terms from
   the fixed word list.
3. **Page: an attribute on every AI-written element.** `data-ai-generated="true"`, plus `data-ai-model` when the
   model is known (`src/lib/aiMark.ts`). The guide answer and the question back carry `data-ai-provider` and
   `data-ai-models`. Marked: reasons, requirements (chips and checks), the question back and its options, comparison
   aspects and cells, explanation sentences, the prompt, the planned search query (not our "Searched the catalog
   for:" around it) and the research trail's queries ([0044](0044-research-trail.md)), AI summaries and values extracted
   by AI in the result list, the preview and on the resource page, the search's translation of the query in "Also
   searched for", and the instructions of the setup guide's steps ([0031](0031-setup-guide.md);
   `ToolGuide.model` names the model in the API). Also the name and the search in the "not found" notice
   (`GUIDE_SUBJECT_NOT_FOUND`): the planner picked them, so they are their own fields (`notices[].subject`,
   `notices[].query`), and the page puts them in our sentence ("X was not found in our sources; showing tools for:
   Y"), each in a marked element. A notice's `message` is our own words (except `QUERY_EXPANDED`, whose terms can hold the model translation): the page never shows it for these
   codes, so an older answer from the browser cache ([0039](../frontend/0039-frontend-recent-answers-cache.md)) says the sentence without the
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

## Consequences
- A crawler, a browser extension or a regulator can find AI-written text in the page and in the API without guessing.
- The text itself stays clean: copy and paste, search and screen readers work as for any other text.
- A new AI-written field goes into `AI_WRITTEN_FIELDS` and is marked in the UI.
- The setup guide ([0031](0031-setup-guide.md)): the model writes each step's instruction, so the instruction is
  labelled and marked; the quotes and commands it shows are copied from the tool's own documents and are not.

## Alternatives considered
- **Invisible Unicode watermark in the text** — breaks copy and paste, search and screen readers, and anyone can
  strip it.
- **Signed provenance manifest per answer** (C2PA-style) — the strongest proof of origin, but no common tooling
  for text yet, and it needs key management. On the roadmap.
- **Only the visible labels** — meets Art. 50(1), not Art. 50(2).

## Code
- `api/openapi.yaml` — `AiContentMarker`, `GuideResponse.aiContent`, `QueryInterpretation.translatedQuery`.
- `backend/.../guide/web/GuideDtoMapper.java` — builds the marker.
- `backend/.../search/web/SearchController.java` — `translatedQuery` apart from the word-list terms
  (`AnalyzedQuery.translation`, `lexiconTerms`).
- `frontend/src/lib/aiMark.ts` — the page attributes; used in `GuideAnswer.tsx`, `Clarify.tsx`, `Results.tsx`,
  `Resource.tsx`, `ResultsTimeline.tsx`, `ResourcePage.tsx`.

## Diagrams
[flow](../../diagrams/flow-0038-ai-content-marking.svg) · [sequence](../../diagrams/sequence-0038-ai-content-marking.svg)

## Roadmap
- [Signed provenance manifest for AI-written text](../../roadmap.md#4-ai-quality-and-cost)
- Known limits: [roadmap § 9](../../roadmap.md#9-known-limits).
