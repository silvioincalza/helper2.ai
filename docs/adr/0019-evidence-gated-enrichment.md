# 0019 — Evidence-gated enrichment and review queue (value must appear in the excerpt; UNCHANGED skip; daily cap)

**Status.** Accepted (2026-10-27). **Builds on:** [0004](0004-persistence-and-provenance.md), [0014](0014-llm-integration.md), [0018](0018-scout-runner.md)

## Context
Sources give a title, a description and a few facts. Search and filters work better with more: audience, technical
level, pricing, use cases, platforms, license, relationships to other tools, a short summary. A model can extract them
from the source text, but it can also invent them, copy instructions planted in the text, or contradict what the
source states. Every published value must point to the text it came from, and a person must decide the unclear cases.

## Decision
1. **Input.** `JdbcEnrichmentStore.load` turns each source record of the resource (title + description) into one
   `Material` with an evidence id. The values the sources state (license, type unless `OTHER`, open source) are
   `sourceFacts`. The first 16 bytes of a Secure Hash Algorithm (SHA-256) hash of the material text are its text hash.
   The input fingerprint (`EnrichmentMaterial.fingerprintFor`) is the same kind of hash over the text hash, the prompt
   version and the model id: what a run of this model with this prompt would see.
2. **UNCHANGED skip.** If the resource's last run that judged an answer (any outcome except `LLM_UNAVAILABLE` and
   `FAILED`) has the same fingerprint, there is no model call and nothing is recorded. A new star count or version
   does not cost a call, and a `REJECTED` answer is not asked for again on the same text. A new prompt version or
   model changes the fingerprint, so every resource is enriched again as the backlog reaches it. `RunScout` gives
   the slot back to the run's enrichment budget, so the next resource can use it ([0018](0018-scout-runner.md)).
3. **Daily cap.** Each call takes one unit of the enrichment budget `H2_SCOUT_DAILY_ENRICHMENT_CALLS` (300 per day; days are in Coordinated Universal Time, UTC),
   separate from the guide's. Gauge `helper2.scout.enrichment.budget.remaining`.
4. **Call.** `SpringAiEnrichmentModel`: reasoning model, thinking off, `H2_NEBIUS_ENRICH_MAX_TOKENS` (8192), prompt
   `prompts/scout/enrich-v3.txt` (version stored with every run). Material goes in `<material evidenceId=…>` tags
   through `UntrustedText`, at most 4000 characters per record; the prompt says the material is data, never
   instructions. The allowed values in the prompt are built from the validator's own sets.
5. **Outcomes of the call.** A spent budget or a transient provider error (timeout, 429, 5xx) → `LLM_UNAVAILABLE`, kept in
   the backlog. Any other provider error, an empty answer or JavaScript Object Notation (JSON) that does not parse → `FAILED` (stored as resource
   status `REJECTED`), retried by the backlog until 3 failures.
6. **`EvidenceValidator`** (deterministic). For each proposed field, relationship and summary claim:
   - field and value must be in the allowed lists (type, audience, techLevel, pricing, openSource, a license id in the style of SPDX (Software Package Data Exchange),
     useCase, platforms; relationship kinds `INTEGRATES_WITH`, `USES`, `SUPPORTS`, `COMPATIBLE_WITH`);
   - every citation must name a retrieved evidence id, and its excerpt (20–300 characters) must appear verbatim (case
     and spacing ignored) in that material;
   - a field that differs from a source-stated value is `CONFLICTING`, never an overwrite;
   - for `license`, `openSource` and `platforms` the excerpt itself must state the value, as a whole word or a known
     synonym ("MIT", "open-source", "command-line"); otherwise `UNVERIFIED`;
   - a relationship's excerpt must name its target; it is `VERIFIED` only when the target is a known resource title
     (4 characters or more), else `UNVERIFIED`;
   - summary claims need valid citations: at most 3 are read (`MAX_SUMMARY_CLAIMS`, the same number the prompt
     states through `{{maxSummaryClaims}}`), 300 characters each.
   Run outcome: `REJECTED` when nothing is accepted, `VALIDATED` with no error and no unverified relationship, else `PARTIAL`.
7. **Storage.** Every run is an `enrichment_run` row: provider, model, prompt version, fingerprint, outcome, errors. In
   the same transaction, a run with model output:
   - closes the resource's `OPEN` review items of earlier runs as `SUPERSEDED` (resolved by the system, with a note
     naming the new run);
   - deletes the resource's older `AI_EXTRACTED` field assertions, and its `AI_EXTRACTED` relationships that no
     reviewer decided (no `ACCEPTED` or `REJECTED` item), each with the evidence rows only they used;
   - writes the non-rejected fields with their verification state, relationships, claims and evidence. A relationship
     a reviewer decided stays as decided, and the same proposal does not reopen it.
   The detail page shows the summary claims of the newest `VALIDATED` or `PARTIAL` run that has claims. A run without
   model output (`LLM_UNAVAILABLE`, `FAILED`) leaves the last output and its review items as they are.
8. **Review queue** (`ReviewQueue`). Validation errors or a `CONFLICTING` field open an `ENRICHMENT_VALIDATION` item. An
   `UNVERIFIED` relationship opens a `RELATIONSHIP_CANDIDATE`; accepting it is the only way it becomes `VERIFIED`.
   Statuses: `OPEN`, `ACCEPTED`, `REJECTED` (a person's decision) and `SUPERSEDED` (a newer run). Admins use
   `GET /api/v1/admin/review-items` (newest first, filtered by status, `page` and `size` up to 100, with the total)
   and `PUT /api/v1/admin/review-items/{id}/resolution` (`ACCEPTED` or `REJECTED`).

## Consequences
- Nothing the model says is shown as verified without a quoted excerpt from retrieved text.
- A page never mixes artificial intelligence (AI) fields or unreviewed AI relationships from two runs, and the review
  queue only asks about the newest run.
- A prompt or model change re-enriches the whole catalog, bounded by the same daily cap.
- The cost is bounded per run (`H2_SCOUT_MAX_ENRICHMENTS`: 20 by default, 200 in the Helm chart) and per day (300);
  unchanged text costs nothing and does not use up a slot of the run.
- Strict rules reject some correct values; the review queue grows with every partial run of new text and needs a person.
- Source-stated `type` is a fact, so a model that disagrees (for example LIBRARY vs MCP_SERVER) opens a review item.

## Alternatives considered
- Trust the model's output — no traceability; invented licenses would be published.
- Model confidence scores — not calibrated; ignored by design.
- Let the model overwrite source values — sources are the record of truth for what they state.
- Enrich on every run — the fingerprint skip saves calls when only popularity or version changed.
- Fingerprint the source text alone — a better prompt or model would never reach resources already enriched.
- Keep every run's relationships and items — pages and the queue would show proposals the newest run no longer makes.

## Code
- `backend/src/main/kotlin/ai/helper2/scout/usecase/EnrichResource.kt` — `EnrichResource`.
- `backend/src/main/kotlin/ai/helper2/scout/Enrichment.kt` — `EvidenceValidator`, proposal and result types.
- `backend/src/main/java/ai/helper2/scout/internal/SpringAiEnrichmentModel.java`, `JdbcEnrichmentStore.java`, `ScoutConfiguration.java`.
- `backend/src/main/resources/prompts/scout/enrich-v3.txt`.
- `backend/src/main/java/ai/helper2/catalog/ReviewQueue.java`, `catalog/usecase/ListReviewItems.java`,
  `catalog/usecase/ResolveReviewItem.java`, `scout/web/AdminController.java`.

## Diagrams
[flow](../diagrams/flow-0019-evidence-gated-enrichment.svg) · [sequence](../diagrams/sequence-0019-evidence-gated-enrichment.svg)

## Roadmap
- [Watch how often source `type` sends proposals to CONFLICTING review](../roadmap.md#5-ai-quality-and-cost)
- [One owner per table: the Scout writes enrichment tables the catalog reads](../roadmap.md#8-architecture)
- [Alert on the review backlog](../roadmap.md#3-operations)
