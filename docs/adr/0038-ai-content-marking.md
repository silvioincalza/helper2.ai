# 0038 — Marking AI-written text: labels on screen, metadata in the API and the page

**Status.** Accepted (2026-10-30). **Builds on:** [0003](0003-contract-first-rest-api.md), [0014](0014-llm-integration.md), [0015](0015-ai-guide-evidence-gate.md), [0019](0019-evidence-gated-enrichment.md)

## Context
The EU AI Act applies to Helper2.ai because people in the EU use it. Helper2.ai builds the guide on top of NVIDIA
Nemotron and offers it under its own name, so it is most likely the provider of an AI system that writes text.
Art. 50 has applied since 2 August 2026:
- Art. 50(1): people must know they deal with AI. The UI already says so ("Ask the AI guide", "AI reasoning",
  "AI summary", "Extracted by AI").
- Art. 50(2): AI-written text must also be marked in a machine-readable way, so it can be detected, as far as
  technically feasible. Nothing did this.

The Commission's Code of Practice on marking AI content (June 2026) is voluntary. It asks for marking, two layers
where needed (metadata, watermark or similar), and a way to detect it.

We call Nemotron through Nebius Token Factory. We do not control sampling, so we cannot add a statistical watermark
to the text.

## Decision
1. **API: one marker per guide answer.** `GuideResponse.aiContent` (`AiContentMarker`) is present when a model wrote
   text in the answer, and absent otherwise. It names the provider, the models, the AI-written fields as JSON paths
   (`GuideDtoMapper.AI_WRITTEN_FIELDS`) and the page that explains the marking (`/mission`, a path, so any host
   works). The streamed `answer` event carries the same object.
2. **API: catalog values keep their origin.** `ResultCard.descriptionOrigin` and `Assertion.origin` already say
   `AI_SUMMARY` or `AI_EXTRACTED`, and `AiSummary.model` names the model. No change.
3. **Page: an attribute on every AI-written element.** `data-ai-generated="true"`, plus `data-ai-model` when the
   model is known (`src/lib/aiMark.ts`): every field the API lists as AI-written (search query, requirements, reasons,
   prompt, clarification question and options, comparison cells, explanation sentences), AI summaries and values
   extracted by AI. The guide answer carries `data-ai-provider` and `data-ai-models`.
   Quotes, titles and links are never marked: they come from the sources.
4. **Explained in public.** The Mission page has a section "How we mark AI text".
5. **No hidden characters in the text.** We do not add zero-width or other invisible characters.

## Consequences
- A crawler, a browser extension or a regulator can find AI-written text in the page and in the API without guessing.
- The marking is lost when someone copies the text. That is the main weakness of metadata. Whether metadata alone
  is "enough" for text is not settled; a lawyer should confirm it.
- A new AI-written field must be added to `AI_WRITTEN_FIELDS` and marked in the UI. A field that is missed is not
  marked; the tests cover the current fields only.
- The setup guide ([0031](0031-setup-guide.md)) only picks and orders passages from the tool's own documents, so it
  writes no new text and is not marked.

## Alternatives considered
- **Invisible Unicode watermark in the text** — breaks copy and paste, search and screen readers, and anyone can
  strip it.
- **Signed provenance manifest per answer** (C2PA-style) — the strongest proof of origin, but no common tooling
  for text yet, and it needs key management. On the roadmap.
- **Only the visible labels** — meets Art. 50(1), not Art. 50(2).

## Code
- `api/openapi.yaml` — `AiContentMarker`, `GuideResponse.aiContent`.
- `backend/.../guide/web/GuideDtoMapper.java` — builds the marker.
- `frontend/src/lib/aiMark.ts` — the page attributes; used in `GuideAnswer.tsx`, `ResultCardView.tsx`, `ResourcePage.tsx`.

## Diagrams
[flow](../diagrams/flow-0038-ai-content-marking.svg) · [sequence](../diagrams/sequence-0038-ai-content-marking.svg)

## Roadmap
- [Signed provenance manifest for AI-written text](../roadmap.md#5-ai-quality-and-cost)
