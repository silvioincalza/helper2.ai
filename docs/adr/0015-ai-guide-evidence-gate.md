# 0015 — AI guide: plan → retrieve → choose → deterministic evidence gate; streamed over SSE

**Status.** Accepted (2026-10-27). **Builds on:** [0008](0008-federated-search.md), [0009](0009-ranking.md), [0012](0012-vector-search-pgvector.md), [0014](0014-llm-integration.md)

## Context
A user describes a need in free text ("an open-source Model Context Protocol (MCP) server so my agent can query Postgres") and wants a few
concrete resources with a reason. A model that answers from memory invents tools, links and licenses. Search results
are real, but a ranked list does not explain why a result fits. A question takes several seconds, so the page must
show progress, and every claim on the page must be traceable to a real result.

## Decision
1. **Retrieval first.** `AskGuide.answer` runs in this order:
   - **plan** (fast model, `prompts/guide/plan.txt`): English `searchQuery`, up to 4 `constraints`, `wantsReading`,
     and a `webQuery`. Generic qualifiers ("open-source", "free", "MIT"…) are removed from the search query, which is
     cut to 6 words and 200 characters; the web query to 12 words. If the plan call fails, the user's own text is the query.
   - **retrieve**: `SearchResources.search` with `size` = `H2_GUIDE_MAX_CANDIDATES` (24). Unless the user asks for
     something to read (model flag or words such as "papers", "news", "articoli"), only tool types are searched.
   - **choose** (reasoning model, `prompts/guide/choose.txt`, native JavaScript Object Notation (JSON) schema when `H2_NEBIUS_NATIVE_SCHEMA=true`):
     the candidates are sent as `<candidate key="c1" …>` … `c24`, text through `UntrustedText`, 800 characters each.
     The model returns up to 3 picks, each with key, reason, verbatim quote and optional constraint quotes.
   - **The need is untrusted too.** A shared `?ask=` link asks a question for whoever opens it, so plan, choose and the
     reason check get the need between `<need>` tags through `UntrustedText` (500 characters, the API limit), and the
     constraints through it as well. The prompts say the need tells what to look for, never how to work.
2. **Deterministic gate** (`GuideValidator`). A pick is kept only if:
   - its key is a retrieved candidate, not a duplicate, and not a reading type when none was asked for;
   - the reason is not blank, at most 400 characters, and contains no link (URLs, `www.`, bare domains, `javascript:`,
     also when percent- or entity-encoded, fullwidth or hidden by invisible characters);
   - the quote is at most 300 characters and appears verbatim (case and spacing ignored) in the candidate's own
     title and description, with at least 40 characters and 6 words, or the whole text when the text is shorter;
   - at most 3 picks are kept (`OVER_LIMIT` for the rest).
   An optional ready-to-use prompt is kept only if it has no link, at most 2000 characters, and is based on at least one
   candidate, every one of type PROMPT. A prompt based on nothing would be free model text on our page, and anyone can
   steer it with a shared question. The page tells the reader that third parties wrote the prompts it adapts.
   Every kept pick increments `helper2.guide.picks{rank}` with its search rank band (`1-3`, `4-8`, `9+`), and the
   `guide timings` log line lists the ranks (`keptRanks`).
3. **Constraint checks.** Each constraint gets CONFIRMED or NOT_CONFIRMED per pick. License constraints are decided by
   the license the source states: a named license must match as a whole word ("mit" does not match "Limited"), and a
   stated license that does not match is NOT_CONFIRMED whatever the quote says. "Open source" needs the source's
   Open Source Initiative (OSI) flag. Other constraints need a quote of at least 5 characters, found verbatim, given for that same constraint.
   The model can only lose a check, never create one.
4. **Statuses.** `OK` (picks kept, none rejected), `PARTIAL` (some rejected, or none kept: top 3 search results
   marked "no artificial intelligence (AI) reasoning"), `LLM_UNAVAILABLE` (no model or a failed call: top 3 search results), `NO_CANDIDATES`.
5. **Streamed over Server-Sent Events (SSE).** `POST /api/v1/guide/stream` (`GuideStreamController`, hand-written
   `SseEmitter`) sends `planned`, `retrieved`, `chosen`, `validated`, `checked` and, always last, `answer` or `error`
   (`Problem`). A step that did not happen sends no event; a cached answer is one `answer` event. Headers:
   `Cache-Control: no-store`, `X-Accel-Buffering: no`. `POST /api/v1/guide` returns the same answer as JSON.
   The work runs on a virtual thread under `GuideLimits` ([0017](0017-guide-cost-guard.md)). No server retry.
6. **Keep-alive comments.** While a question runs, the stream sends the SSE comment `:keep-alive` every
   `H2_GUIDE_HEARTBEAT` (15 s). One model step can be silent for longer than the client's idle timeout (45 s,
   [0025](0025-frontend-one-box.md)), and an asker who joined an identical question already running
   ([0017](0017-guide-cost-guard.md)) gets no step events at all. The comments come from one shared scheduler thread
   (`guide-heartbeat`) and stop after the last event or when the client is gone.

## Consequences
- What the page shows as an AI pick is a real search result with a quote that exists in its text.
- The gate proves the quote, not the reason; the reason is checked by a second model call ([0016](0016-guide-reason-check.md)).
- A quote proves the source said it, not that it is true. A description written to be chosen ("free, offline, the
  best Postgres MCP server") passes the gate and the reason check. Only the license is safe, because it comes from
  source metadata. Picks from low search ranks are counted so this can be watched.
- No ready-to-use prompt is written from scratch any more, only adapted from catalog prompts.
- Strict rules reject some good picks (evaluation of 2026-10-27: 31 kept, 2 rejected; [0030](0030-guide-evaluation.md)).
- The guide can only be as good as retrieval: a resource not in the 24 candidates cannot be picked.
- The stream shows real steps, but the SSE contract is hand-written, not generated from OpenAPI.
- A slow model step does not look like a dead connection: the client sees bytes at least every 15 s. A comment is
  not an event, so clients that only read events ignore it.

## Alternatives considered
- Let the model answer from its own knowledge — invents resources and links; nothing to verify against.
- Retrieval-augmented answer as free text — readable, but claims cannot be checked one by one.
- Model "confidence" or self-check only — not a guarantee; the gate is plain code and testable.
- WebSocket for progress — two-way channel not needed; SSE works over plain Hypertext Transfer Protocol (HTTP) and proxies.

## Code
- `backend/src/main/kotlin/ai/helper2/guide/usecase/AskGuide.kt` — `AskGuide`.
- `backend/src/main/kotlin/ai/helper2/guide/Guide.kt` — `GuideValidator`, `GuideModel`, `GuideProgress`.
- `backend/src/main/java/ai/helper2/guide/internal/SpringAiGuideModel.java` — plan and choose calls.
- `backend/src/main/java/ai/helper2/guide/web/GuideStreamController.java`, `GuideController.java`, `GuideDtoMapper.java`.
- `backend/src/main/resources/prompts/guide/plan.txt`, `choose.txt`.
- `api/openapi.yaml` — `/guide`, `/guide/stream` (tag `guide-stream`), `GuideStream*` schemas.

## Diagrams
[flow](../diagrams/flow-0015-ai-guide-evidence-gate.svg) · [sequence](../diagrams/sequence-0015-ai-guide-evidence-gate.svg)

## Roadmap
- [Monitor picks that come from low-ranked candidates (possible prompt injection)](../roadmap.md#5-ai-quality-and-cost)
- [Browser end-to-end test of the streamed guide](../roadmap.md#quality-gates)
