# 0056 — Evidence ladder: every field from the records, the README, then the product's own pages

**Status.** Accepted. **Builds on:** [0019](0019-evidence-gated-enrichment.md), [0033](../foundation/0033-resilience-policies.md), [0037](../search/0037-tavily-store-and-daily-quota.md), [0038](../guide/0038-ai-content-marking.md), [0043](0043-enrichment-gap-round.md), [0051](0051-tavily-discovery.md). **Amends:** [0019](0019-evidence-gated-enrichment.md) (validator rules, prompt v4), [0043](0043-enrichment-gap-round.md) (trigger, more steps).

## Context
A product page on Helper2 shows pricing, platforms, licence, use cases, who it is for and its technical level. Most
of these come from enrichment ([0019](0019-evidence-gated-enrichment.md)), and enrichment sees only what the sources
store: a title and a short excerpt. The gap round ([0043](0043-enrichment-gap-round.md)) adds the README or model card,
but only for repositories and models, and only when a run ends `PARTIAL` or `REJECTED`. A product a person accepted
from discovery ([0051](0051-tavily-discovery.md)) is a web page: no README, and its price is on its pricing page.
So most product pages say "Not stated" for almost everything.

The goal is every field filled, with no hallucinations. Facts (price, licence, open source, platforms, type, use case,
relationships) need a quote that states them, or they stay "Not stated". Who a resource is for and how technical it is
are judgements: a model may estimate them, and the page says so. The description may be AI-written, labelled.

## Decision
1. **When the ladder climbs.** After the first run, inside the same `EnrichResource` attempt, when a target fact of the
   type is still missing (`FieldTargets.forType`, pure code) or the run is `PARTIAL` or `REJECTED` (as in 0043):
   - `PRODUCT`, `AGENT`: pricing, platforms, use case;
   - `MCP_SERVER`, `SKILL`, `LIBRARY`: licence, open source, platforms, use case;
   - `MODEL`, `DATASET`: licence, open source, use case; `PROMPT`: use case;
   - reading types (articles, papers, news, events, courses): none, their facts come from their sources.
   A fact the sources state (licence, type, open source) is not missing. Audience and tech level never make it climb.
2. **The steps.** Each step is one more model call, and each keeps the run that keeps more (as in 0043). At most three
   model calls per attempt; the attempt saves once, under the fingerprint of the records alone.
   - Step 1: the records (as before).
   - Step 2: the README or model card (0043). A discovery record (`discovery-<category>`, `WebSources.isDiscovery`) whose URL is `github.com/<owner>/<repo>` now
     counts too: a person accepted it.
   - Step 3: the product's own pages, when a record is one (`PageTargets`: `discovery-<category>` and `curated` records,
     not a registry, a repository or an article). New gap kind `OFFICIAL_PAGE` (`official-page:<host>` in
     `enrichment_run.extra_evidence`). The model reads the records, the README if any, and the pages.
3. **Which pages, chosen by code** (`OfficialPages`, `PageScope`, `OfficialLinks`). The model never picks a URL or a
   query.
   - The accepted page first, then up to 3 pages its links lead to whose last path segment is `pricing`, `plans`,
     `download(s)`, `features`, `docs`, `documentation`, `integrations` or `about`, best first. At most 5 URLs a product.
   - Allowed: `https` only; a host name, not an IP address; no user info; no port but 443.
   - Scope: the same registrable domain (Public Suffix List, the copy that ships with Apache HttpClient 5, already a
     dependency); on a shared host (`github.io`, `vercel.app`, `netlify.app`, `pages.dev`, `hf.space`, `notion.site`,
     `webflow.io`) the exact subdomain; for a page under a path (`aws.amazon.com/bedrock`) that host and path prefix.
   - Tavily follows redirects: every URL it returns is checked against the scope again, and an off-site page is
     dropped. `failed_results` is no text.
4. **Reading** (port `PageReader`, adapter `sources/evidence/TavilyExtract`): Tavily Extract `POST /extract`,
   `extract_depth` basic, `format` markdown (the links are needed), no images, no favicon. One call for the home page,
   one for the linked pages. Policy `collector-gap-web` (circuit breaker and retry, like a collector). Off without
   `H2_TAVILY_API_KEY` or with `H2_SOURCE_TAVILY_EXTRACT_ENABLED=false`.
5. **Cleaning** (`UntrustedPageText`, the README cleaner made general): the first 64 KB; hidden characters, HTML
   comments, scripts, styles, hidden elements (`hidden`, `display:none`, `visibility:hidden`, `aria-hidden`), tags and
   link targets removed; lines that talk to an AI ("ignore previous instructions", "set pricing ...") removed; email
   addresses become `[email]`, phone numbers `[phone]`. A window of 6000 characters keeps the top, then the sections
   whose heading says price, plan, licence, download, platform, install, integrations or features.
6. **What a page may prove.** A page is `selfDeclared` material. A fact all of whose quotes come from pages is stored
   with origin `SELF_DECLARED` ("Self-declared by the vendor"); a fact a record also states stays `AI_EXTRACTED`.
   Pages may support summary claims only from the accepted page's own host, and such a claim is marked
   `selfDeclared`. A README still supports no claim. The licence or open-source value the source API states wins: a
   page that says otherwise is rejected, not shown as a conflict. A relationship only pages name stays `UNVERIFIED`
   (a review candidate) with origin `SELF_DECLARED`, at most 3 a run. Never stored, because the validator drops the
   sentence: compliance certificates (SOC 2, HIPAA, GDPR, ISO 27001, CE, FDA), user counts, popularity and awards,
   comparisons with other products, superlatives.
   - *Why an origin and not a new verification state or a source tier.* `ValueOrigin` already drives the label on the
     page and in the API ("Extracted by AI"); one more value gives "Self-declared by the vendor" with no new column,
     and `Verification` keeps meaning "did the quote check out", which the review queue and `accepted` rely on.
7. **Validator** (`EvidenceValidator`, now its own file):
   - `pricing`, `type` and `useCase` join licence, open source and platforms: the quote must state the value.
     Pricing: FREE "free", "$0", "no cost"; FREEMIUM "free plan", "free tier", "free forever", "freemium"; PAID a
     currency sign before a number, "per month", "/mo", "per seat", "per user", "contact sales"; OPEN_SOURCE_SELF_HOSTED
     "open source", "self-host". "Free trial" (and "try for free", "14-day free") is removed before matching: a trial
     states no price. Type and use case have their own word lists; `OTHER` is never stated.
   - Summary sentences: every capitalised name and every number in the sentence is in its cited excerpts (or the
     title), and at least half of its content words are; no superlative, certificate, popularity or comparison.
     A sentence that fails is dropped.
8. **Judgement fields.** `audience` and `techLevel` may be proposed without a quote. They are stored with origin
   `AI_ESTIMATED` and verification `UNVERIFIED`, never `VERIFIED`, and never counted as accepted. A fact is never
   `AI_ESTIMATED`. As in [0038](../guide/0038-ai-content-marking.md): the page labels them "AI-estimated" and marks
   them `data-ai-generated`; the API says `origin: AI_ESTIMATED` (OpenAPI enum `ValueOrigin`).
9. **Budgets and cost.**
   - Model calls 2 and 3 take the gap cap (`H2_SCOUT_GAP_DAILY_CALLS`, inside the enrichment cap).
   - Extract credits: `helper2.scout.evidence.daily-extract-credits` (`H2_SCOUT_EVIDENCE_DAILY_EXTRACT_CREDITS`, 20).
     Each credit also counts on the global Tavily credit counter of live search ([0037](../search/0037-tavily-store-and-daily-quota.md),
     `H2_TAVILY_GLOBAL_DAILY_CREDITS`), and on `helper2.tavily.credits{feature=scout-evidence,tier=none}`. When the
     global counter cannot answer, no page is read. Gauge `helper2.scout.evidence.credits.remaining`.
   - **The one exception to "no page text stored".** The cleaned text of a product's pages (what `UntrustedPageText`
     left: hidden text gone, emails and phone numbers masked, cut to the window) is kept in Redis only, under a SHA-256
     of the accepted page's URL, for `page-text-ttl` (`H2_SCOUT_EVIDENCE_PAGE_TEXT_TTL`, PT24H; more than P1D stops
     startup). Only to retry: when the model is down or the gap cap is spent, the next attempt within the day reads the
     cache and pays no credit. The raw page is never kept, and the text never goes to Postgres, logs or metrics.
   - A page a saved run read is not read again for 30 days (`page-read-ttl`, `H2_SCOUT_EVIDENCE_PAGE_READ_TTL`): the
     shared cache (`ai_cache`, name `scout-page-read`) keeps a hash of the URL and a hash of its text, never the text.
     It is written only after the run is saved: a failed model call leaves no entry, so the page can be read again
     once the 24-hour text is gone. One plan per fingerprint: the `UNCHANGED` skip stays.
   - Products a person accepted from the review queue go first in the enrichment backlog.
10. **Prompt.** `enrich-v4`: vendor pages are marked `kind="vendor-page"`, pricing, type and use case need the value
    in the quote, a free trial is not a free plan, audience and tech level may be estimated, sentences keep to their
    excerpts. The output schema is unchanged: an estimate is a field with no citation.
11. **Targeted re-enrichment, not the whole catalog.**
    - The fingerprint no longer holds the prompt version. It holds a fixed epoch, `enrich-v3`, the version the stored
      fingerprints were made with, so they all stay valid: a new prompt alone sends nothing to the model. (A new model
      or new source text still does.) To re-enrich everything on purpose, change the epoch.
    - `RecheckEnrichments` applies the new rules to what earlier runs stored, by code, with no model call: a verified
      pricing, type or use case whose quotes do not state it is set to `REJECTED` (it shows "Not stated"); a summary
      sentence that fails the claim check is dropped, with the evidence only it used.
    - A resource goes back to the model only when it still misses a target fact (`FieldTargets`), has more to read
      (a README or model card, or its own pages) and its last run used an older prompt. It is set back to `NOT_RUN`:
      the backlog takes it, accepted products first, inside the daily caps, and the unchanged fingerprint does not
      skip it: `resource.enrichment_rerun_requested_at` makes the runs judged before the request no
      longer count for the UNCHANGED skip; the next run after it does again.
    - It runs once after the first start where the Scout is scheduled (marker `recheck-enrichment-enrich-v4` in the new
      table `maintenance_task`, claimed once across replicas), and whenever an admin presses "Re-check stored
      answers" (`POST /api/v1/admin/enrichment-recheck`, audit action `RECHECK_ENRICHMENT`). Idempotent: a second
      run changes nothing. Log line and counter `helper2.scout.recheck{result=checked|fields_rejected|claims_dropped|runs_requested}`.
    - *Why this way.* Taking the prompt out of the skip is one line, and the epoch keeps every stored fingerprint, so
      no one-time selection of "everything" is needed. A marker column per resource or a separate queue would add
      state for a job that runs once.
12. **Observability.** Counter `helper2.scout.fields{type,field,state,step}` per target and judgement field of the
    kept run: `state` = `verified`, `ai_estimated`, `rejected` or `unknown`; `step` = `source`, `records`, `readme` or
    `pages`. Counter `helper2.scout.gap` gets source `official-page` with outcomes `filled`, `not_better`, `no_text`,
    `source_failed`, `model_failed`, `skipped_cap`, `skipped_seen`, `not_allowed`, and `cached` when a retry used the
    24-hour text.
13. **Page.** The resource page has a "Fields" panel: each field shows its value and where it comes from ("From the
    source", "Self-declared by the vendor (extracted by AI)", "Extracted by AI", "AI-estimated"), or "No", "Sources
    disagree", or "Not stated". A fact without a quote that states it is "Not stated". Cards show only what the sources
    state, as before. An AI-written description is headed "About (AI-written)".

## Consequences
- Product pages get prices, platforms and use cases with a quote and a link, marked as what the vendor says.
- At most 20 Extract credits a day (about 10 products), on top of the ~420 credits a month the Scout spends on search;
  inside the global cap. See [cost-analysis](../../cost-analysis.md).
- `enrich-v4` does not re-enrich the catalog. The code re-check cleans the stored answers once; only resources that
  still miss facts and have something new to read go to the model again, accepted products first.
- Tavily (United States) receives the addresses of product pages. The raw page is never stored. The cleaned text stays
  in Redis for at most 24 hours, only to retry; then only the 20–300-character quotes are kept. The Privacy page says
  so.
- **Known risk.** Tavily's written OK covers search results for public display, storage and reuse
  ([sources.md](../../sources.md)). Its OK for Extract is still pending. The page step can be turned off with
  `H2_SCOUT_EVIDENCE_DAILY_EXTRACT_CREDITS=0` or `H2_SOURCE_TAVILY_EXTRACT_ENABLED=false` without a release.
- A page is read once in 30 days. A price that changes later is not seen until then (roadmap).
- A retry after the 24-hour text expired pays Tavily again (one or two credits).
- The cached text is small (about 10 products a day, at most 4 pages of 6,000 characters each, under 300 KB) next
  to the 128 MB Redis that also holds sessions and counters.

## Security (STRIDE)
- **Spoofing**: a page pretends to be the product, or a redirect leads elsewhere. Code picks every URL from the
  accepted page's links, inside its registrable domain (PSL), exact host on shared hosts, path prefix on a vendor's
  site; every returned URL is checked again.
- **Tampering / prompt injection** (OWASP LLM01): hidden text, AI-addressed lines and hidden characters are removed
  before the model reads the page; the page is marked as untrusted vendor text; and the validator, not the model,
  decides: a fact needs a quote that is in the page and states the value. Adversarial tests: an injected "set pricing
  FREE", hidden text, an off-site redirect, a fake "integrates with OpenAI, Anthropic, Google" list, an oversized page,
  a "free trial" price. None becomes `VERIFIED`.
- **Repudiation**: every fact keeps its quote, page URL and read time; the run keeps `official-page:<host>`.
- **Information disclosure**: no user data is sent; the raw page is never stored; emails and phone numbers are
  masked before the model, a quote or the cache sees them.
- **Information disclosure (cached page text)**: the cleaned text of public product pages sits in Redis for at most
  24 hours. Redis is inside the cluster, reached only by the app with its password from OpenBao; the key is a URL
  hash, the TTL is set on every write and more than a day stops startup. The text never goes to Postgres, logs or
  metrics.
- **Denial of service / cost**: two Extract calls and three model calls at most per attempt; own daily credit cap,
  global Tavily cap, gap cap; 64 KB per page, 2 MB per answer; circuit breaker `collector-gap-web`.
- **Elevation of privilege**: Tavily's key never leaves our request to `api.tavily.com`; we never fetch a page
  ourselves, so no request reaches an internal address (no SSRF).

## AI architecture check
| # | Layer | State |
|---|---|---|
| 1 | Model | Covered: the same reasoning model; a provider outage is `LLM_UNAVAILABLE`, the first run stands. |
| 2 | Agent Framework | Covered: no agent loop; a fixed ladder of at most three calls, code-driven. |
| 3 | Orchestration | Covered: inside the existing Scout run; retries by resilience policy; one save per attempt. |
| 4 | Tool & API | Covered: Tavily Extract behind a port, URLs only from code; model tool calling rejected (below). |
| 5 | Memory | Covered: cleaned page text in Redis for at most 24 h (retry only); the ledger keeps URL and text hashes for 30 days. |
| 6 | Knowledge & RAG | Covered: grounding by quotes from retrieved pages; no vector search needed here. |
| 7 | Data | Covered: `V11` migration (origins, `summary_claim.self_declared`, `maintenance_task`); evidence anchored to its record (0034); a code re-check of stored answers. |
| 8 | Browser & Computer Use | Covered: none; Tavily reads the page, we never run a browser. |
| 9 | Guardrails | Covered: URL scope, cleaning, stating words, claim checks, caps on relationships, source facts win. |
| 10 | Observability | Covered: `helper2.scout.fields`, `helper2.scout.gap`, credits counter and gauge; no page text in logs. |
| 11 | Evaluation | Deferred: adversarial unit tests now; a fill-rate eval set is on the roadmap (needs real runs first). |
| 12 | Deployment | Covered: config only (`H2_SCOUT_EVIDENCE_*`, `H2_SOURCE_TAVILY_EXTRACT_*`), on by default, off without a key. |
| 13 | Security & Identity | Covered: Tavily key from OpenBao as before; no new secret. |
| 14 | Human-in-the-Loop | Covered: self-declared relationships wait in the review queue; validation errors open review items. |
| 15 | Cost & Reliability | Covered: own credit cap, global Tavily cap, gap cap, 30-day ledger, circuit breaker. |

## Alternatives considered
- **Model tool calling** (the model asks for pages or searches) — flexible, but the model would choose what to read,
  an injected page could steer it to any URL, and the cost would not be bounded by code. Rejected.
- **Fetch pages ourselves** (our own HTTP client and HTML parser) — no third party, but we would need SSRF protection,
  robots and rate limits per site, JavaScript-heavy pages would be empty, and our servers' address would visit every
  product site. Tavily already does this under its terms. Rejected for now.
- **Tavily site search** (`include_domains`) to find the pricing page — finds pages the home page does not link, but
  costs a search credit per product and returns excerpts, not pages. Left for PR E, only if the fill rate stays low.
- **A new verification state `SELF_DECLARED`** — would leak into every place that reads verification (review items,
  `accepted`, the badge). An origin is the smaller change.
- **Store the page text for re-reads** — copies third-party text we do not need and a privacy cost; the quotes are
  enough. Only the cleaned text for a retry within a day is kept.
- **Keep the page text only in memory** (no retry cache) — no copy at all, but a model outage after the read pays
  Tavily again, and the read ledger then had to be written at read time, losing the page for 30 days.
- **Keep the prompt version in the fingerprint** — simple, but every prompt change re-enriches the whole catalog:
  days of the daily cap for resources that miss nothing.
- **Let audience and tech level stay empty** — honest, but most products would show nothing useful; an estimate,
  labelled, is more helpful and is never presented as a fact.

## Code
- `backend/src/main/kotlin/ai/helper2/scout/OfficialPages.kt` — `FieldTargets`, `PageTargets`, `PageScope`, `OfficialLinks`, `OfficialPages`, ports `PageReader`, `ExtractBudget`, `PageLedger`, `PageTextCache`, `DomainRoots`.
- `backend/src/main/kotlin/ai/helper2/scout/usecase/RecheckEnrichments.kt` — the code re-check, port `StoredAnswers`; `scout/internal/CatalogStoredAnswers.java`, `catalog/enrichment/StoredEnrichments.java` (implemented by `JdbcEnrichmentResults`); `admin/web/AdminController.java`; `frontend/src/pages/admin/ScoutRunsPage.tsx`.
- `backend/src/main/java/ai/helper2/scout/internal/RedisPageTextCache.java` — the 24-hour cleaned text.
- `backend/src/main/kotlin/ai/helper2/scout/Enrichment.kt` — `FINGERPRINT_EPOCH`.
- `backend/src/main/kotlin/ai/helper2/scout/EvidenceValidator.kt` — stating words, `PricingWords`, `ClaimCheck`, judgement fields, self-declared rules.
- `backend/src/main/kotlin/ai/helper2/scout/usecase/EnrichResource.kt` — the ladder, `helper2.scout.fields`.
- `backend/src/main/kotlin/ai/helper2/scout/GapRound.kt` — `UntrustedPageText`, `GapKind.OFFICIAL_PAGE`.
- `backend/src/main/kotlin/ai/helper2/shared/Vocabulary.kt` — `ValueOrigin.AI_ESTIMATED`, `SELF_DECLARED`.
- `backend/src/main/java/ai/helper2/sources/evidence/TavilyExtract.java`, `PublicSuffixDomains.java`; beans in `sources/config/SourcesConfiguration.java`.
- `backend/src/main/java/ai/helper2/scout/internal/ScoutConfiguration.java` (`officialPages`), `TavilyCreditBudget.java`, `SharedCachePageLedger.java`, `CatalogEnrichmentStore.java` (type, page target), `SpringAiEnrichmentModel.java` (`enrich-v4`, material kind).
- `backend/src/main/resources/prompts/scout/enrich-v4.txt`, `db/migration/V11__evidence_ladder_origins.sql`.
- `backend/src/main/java/ai/helper2/catalog/internal/JdbcEnrichmentResults.java` (origins), `JdbcEnrichmentCandidates.java` (accepted products first).
- `api/openapi.yaml` — `ValueOrigin`, `Relationship.origin`, `SummaryClaim.selfDeclared`.
- `frontend/src/components/results/FieldStates.tsx`, `frontend/src/pages/ResourcePage.tsx`, `frontend/src/lib/aiMark.ts`.
- Tests: `FieldTargetsTest`, `EvidenceValidatorLadderTest`, `OfficialPagesTest`, `EnrichResourceLadderTest` (adversarial pages),
  `TavilyExtractTest`, `PublicSuffixDomainsTest`, `TavilyCreditBudgetTest`, `RecheckEnrichmentsTest`, `RedisPageTextCacheIT`,
  `ScoutPropertiesBindingTest`, `CatalogEnrichmentStoreIT`, `SecurityIT`, `EnrichResourceTest`, `ResourcePage.test.tsx`, `Admin.test.tsx`.

## Diagrams
[flow](../../diagrams/flow-0056-evidence-ladder.svg) · [sequence](../../diagrams/sequence-0056-evidence-ladder.svg) · [architecture: index](../../diagrams/architecture-index.svg)

## Roadmap
- [Evidence ladder follow-ups](../../roadmap.md#4-ai-quality-and-cost): site search (PR E), re-read pages after N days, a fill-rate eval set.
- [Lawyer review of product-page quotes and Tavily Extract](../../roadmap.md#product-and-legal)
