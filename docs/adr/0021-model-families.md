# 0021 — Compare AI models: curated model families with cited specs

**Status.** Accepted (2026-10-27). **Builds on:** [0003](0003-contract-first-rest-api.md), [0004](0004-persistence-and-provenance.md), [0020](0020-scout-collectors.md)

## Context
Users ask "which model should I use": OpenAI GPT, Anthropic Claude, Google Gemini, Meta Llama and others. Specs such
as context window, output limit, price per million tokens, modalities, license and release date change often and are
spread over provider pages. Third-party tables are often out of date and rarely say where a number comes from. No
source application programming interface (API) gives these specs for every provider, and a model must not be asked to fill the gaps.

## Decision
1. **Curated research file, dated.** `docs/research/model-families-2026-10-27.json` holds, per
   provider, the official models page and each fact with its web address (URL) and a verbatim excerpt, read on 2026-10-27. A fact
   with no official page is left out.
2. **Generated seed.** `scripts/model-families-seed.mjs` turns the file into the repeatable Flyway migration
   `R__model_families.sql`; the Structured Query Language (SQL) file is never edited by hand. The script fails for an unknown provider or a family without
   a models page. Per family it upserts a curated `resource` (type MODEL), a `curated` source record and a
   `model_family` row (id, position, provider, name, open weights, provider tags), and replaces the family's `CURATED`
   field assertions and their evidence. Families no longer in the file are deleted. Flyway re-runs the seed when its
   checksum changes; running it twice changes nothing.
3. **Nine families today**, in this order: OpenAI, Anthropic, xAI, Google, Meta, Mistral AI, DeepSeek, Qwen (Alibaba),
   NVIDIA. The seed holds 174 curated assertions.
4. **Spec fields** (assertion field names): `flagship`, `contextTokens`, `maxOutputTokens`, `inputUsdPerMTok`,
   `outputUsdPerMTok`, `inputModalities`, `outputModalities`, `openWeights`, `license`, `knowledgeCutoff`, `released`,
   and `benchmark:<name>`. Each is `CURATED` / `VERIFIED` with one evidence row: URL, excerpt (cut to 500 characters),
   read date.
5. **API.** `GET /api/v1/model-families` (`ListModelFamilies.list(5)` in `ResourcesController`) returns every family in
   order with its card, its specs (only `CURATED` assertions with no source id) and up to 5 "listed models": the newest
   MODEL records of `hf-provider-orgs` tagged with the family's Hub org ([0020](0020-scout-collectors.md)). Specs and
   listed models are never merged. A family whose resource is missing is left out. `Cache-Control: public, max-age=300`.
6. **Page.** `/models` (`ModelsPage`) shows one column per family. A missing field shows "Not stated"; each value links
   to its source page with the excerpt and the read date. Anthropic has no Hub organization, so it lists no models.

## Consequences
- Every number on the page can be traced to a provider page and a date.
- Specs age: they are only as fresh as the research file. Updating means a new research pass, a new file and a
  regenerated seed.
- A gap stays a gap ("Not stated") instead of a guess.
- The listed models are live (Scout), the specs are not; the page labels them separately.
- The seed asserts prices; it is curated data with evidence, not a value taken from a model.

## Alternatives considered
- Ask the large language model (LLM) for specs — invents numbers; no evidence.
- Scrape provider pages on a schedule — each page has its own layout and terms; brittle for nine providers.
- Third-party leaderboard API — unclear provenance and license; not the provider's own statement.
- Hand-written SQL — easy to mistype a number; the script keeps the file as the single source.

## Code
- `docs/research/model-families-2026-10-27.json` — the curated facts.
- `scripts/model-families-seed.mjs` — generator; `backend/src/main/resources/db/migration/R__model_families.sql` — output.
- `backend/src/main/java/ai/helper2/catalog/usecase/ListModelFamilies.java`, `usecase/GetResourceDetail.java`.
- `backend/src/main/java/ai/helper2/catalog/web/ResourcesController.java` — `listModelFamilies`.
- `api/openapi.yaml` — `/model-families`, `ModelFamiliesResponse`, `ModelFamily`.
- `frontend/src/pages/ModelsPage.tsx`.

## Diagrams
[flow](../diagrams/flow-0021-model-families.svg) · [sequence](../diagrams/sequence-0021-model-families.svg)

## Roadmap
- [An admin user interface (UI) for the curated list](../roadmap.md#6-search-and-catalog)
- [Batch the per-item and per-assertion queries of `GetResourceDetail`](../roadmap.md#6-search-and-catalog)
