# 0023 — Evidence-gated enrichment: the gate, the gap round, the evidence ladder, writing about a resource

**Status.** Accepted. Merges old 0019, 0043, 0056, 0062 (texts merged in PR 5 of the reset). Until then each part below is the old record as it was, with its numbers and links moved to the new ones.

<a id="part-0019"></a>

## Part: was ADR 0019 — Evidence-gated enrichment and review queue (value must appear in the excerpt; UNCHANGED skip; daily cap)

**Status.** Accepted. **Builds on:** [0004](../foundation/0004-persistence-schema-and-app-roles.md), [0008](../foundation/0008-llm-integration.md), [0020](0020-scout-runner.md) **Amended by:** [the part that was ADR 0056](#part-0056) (pricing, type and use case need the value in the quote; audience and tech level may be AI-estimated; summary sentences keep to their excerpts; prompt `enrich-v4`), [the part that was ADR 0062](#part-0062) (an article, a news item or a paper keeps only audience, tech level and use case, and names what it is about; prompt `enrich-v5`).

### Context
Sources give a title, a description and a few facts. Search and filters work better with more: audience, technical
level, pricing, use cases, platforms, license, relationships to other tools, a short summary. A model can extract them
from the source text, but it can also invent them, copy instructions planted in the text, or contradict what the
source states. Every published value must point to the text it came from, and a person must decide the unclear cases.

### Decision
1. **Input.** `CatalogEnrichmentStore.load` reads the resource through the catalog's port `EnrichmentCandidates` and
   turns each source record (title, a line break, description) into one `Material` with an evidence id. The values the sources state (license, type unless `OTHER`, open source) are
   `sourceFacts`. The first 16 bytes of a Secure Hash Algorithm (SHA-256) hash of the material text are its text hash.
   The input fingerprint (`EnrichmentMaterial.fingerprintFor`) is the same kind of hash over the text hash, the prompt
   version and the model id: what a run of this model with this prompt would see.
2. **UNCHANGED skip.** If the resource's last run that judged an answer (any outcome except `LLM_UNAVAILABLE` and
   `FAILED`) has the same fingerprint, there is no model call and nothing is recorded. A new star count or version
   does not cost a call, and a `REJECTED` answer is not asked for again on the same text. A new prompt version or
   model changes the fingerprint, so every resource is enriched again as the backlog reaches it. `RunScout` gives
   the slot back to the run's enrichment budget, so the next resource can use it ([0020](0020-scout-runner.md)).
3. **Daily cap.** Each call takes one unit of the enrichment budget `H2_SCOUT_DAILY_ENRICHMENT_CALLS` (500 per day; days are in Coordinated Universal Time, UTC),
   separate from the guide's. Gauge `helper2.scout.enrichment.budget.remaining`.
4. **Call.** `SpringAiEnrichmentModel`: reasoning model, thinking off, `H2_NEBIUS_ENRICH_MAX_TOKENS` (8192), prompt
   `prompts/scout/enrich-v5.txt` (version stored with every run). Material goes in `<material evidenceId=…>` tags
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
7. **Storage, owned by the catalog.** The Scout hands each run to the catalog's port `EnrichmentResults`
   (`record(run, checked items)`); the SQL is in `catalog/internal/JdbcEnrichmentResults`, which also reads the
   results back for the detail page. Every run is an `enrichment_run` row: provider, model, prompt version,
   fingerprint, outcome, errors. In the same transaction, a run with model output:
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
   and `PUT /api/v1/admin/review-items/{id}/resolution` (`ACCEPTED` or `REJECTED`); the decision's SQL is in
   `catalog/internal/ReviewDecisions`. Gauge `helper2.review.open_items` counts `OPEN` items; alert
   `Helper2ReviewBacklog` fires when more than 50 wait for a day.

### Consequences
- Nothing the model says is shown as verified without a quoted excerpt from retrieved text.
- A page never mixes artificial intelligence (AI) fields or unreviewed AI relationships from two runs, and the review
  queue only asks about the newest run.
- A prompt or model change re-enriches the whole catalog, bounded by the same daily cap.
- The cost is bounded per run (`H2_SCOUT_MAX_ENRICHMENTS`: 20 by default, 200 in the Helm chart) and per day (500);
  unchanged text costs nothing and does not use up a slot of the run.
- Source-stated `type` is a fact, so a model that disagrees (for example LIBRARY vs MCP_SERVER) opens a review item.
  Any field that contradicts a source-provided value opens one `ENRICHMENT_VALIDATION` item for the run; there is no
  separate source-conflict kind.

### Alternatives considered
- Trust the model's output — no traceability; invented licenses would be published.
- Model confidence scores — not calibrated; ignored by design.
- Let the model overwrite source values — sources are the record of truth for what they state.
- Enrich on every run — the fingerprint skip saves calls when only popularity or version changed.
- Fingerprint the source text alone — a better prompt or model would never reach resources already enriched.
- Keep every run's relationships and items — pages and the queue would show proposals the newest run does not make.

### Code
- `backend/apps/backoffice/src/main/kotlin/ai/helper2/scout/usecase/EnrichResource.kt` — `EnrichResource`.
- `backend/apps/backoffice/src/main/kotlin/ai/helper2/scout/Enrichment.kt` — `EvidenceValidator`, proposal and result types.
- `backend/apps/backoffice/src/main/java/ai/helper2/scout/internal/SpringAiEnrichmentModel.java`, `CatalogEnrichmentStore.java`, `ScoutConfiguration.java`.
- `backend/apps/backoffice/src/main/resources/prompts/scout/enrich-v5.txt`.
- `backend/libs/catalog/src/main/java/ai/helper2/catalog/enrichment/EnrichmentCandidates.java`, `EnrichmentResults.java` (named
  interface `enrichment`), `catalog/internal/JdbcEnrichmentCandidates.java`, `JdbcEnrichmentResults.java`.
- `backend/libs/catalog/src/main/java/ai/helper2/catalog/ReviewQueue.java`, `catalog/internal/ReviewDecisions.java`,
  `catalog/usecase/ListReviewItems.java`, `catalog/usecase/ResolveReviewItem.java`, `admin/web/AdminController.java`.
- `deploy/monitoring/helper2-rules.yaml` — `Helper2ReviewBacklog`.

### Diagrams
[flow](../../diagrams/flow-0023-evidence-gated-enrichment.svg) · [sequence](../../diagrams/sequence-0023-evidence-gated-enrichment.svg)

### Amended by
- [the part that was ADR 0043](#part-0043): a run that leaves fields unproven reads the resource's README or model card once.

### Roadmap
- [Watch how often source `type` sends proposals to CONFLICTING review](../../roadmap.md#4-ai-quality-and-cost)
- Known limits: [roadmap § 9](../../roadmap.md#9-known-limits).

<a id="part-0043"></a>

## Part: was ADR 0043 — Enrichment gap round: read the README or model card once when a run leaves fields unproven

**Status.** Accepted. **Builds on:** [0020](0020-scout-runner.md), [the part that was ADR 0019](#part-0019), [0021](0021-collectors-and-events.md), [0035](../operations/0035-data-retention.md) **Amended by:** [the part that was ADR 0056](#part-0056) (the ladder also climbs when a target field is missing, and adds the product's own pages as a third step).

### Context
Enrichment ([the part that was ADR 0019](#part-0019)) sees only what the sources store: a title and a short
description. Licence, platforms and relationships are rarely in there, so many runs end `PARTIAL` or `REJECTED`, and
the review queue fills with fields nobody can prove. The resource's own README or model card usually states them.
The idea comes from iterative research tools: look at what is still missing, then fetch more. Here code, not the model,
decides where the extra text comes from, and the same evidence gate judges it.

### Decision
1. **When.** Inside the same `EnrichResource` attempt, after the first run is validated. Only when the outcome is
   `PARTIAL` or `REJECTED` and the resource names its own repository or model. One gap round per attempt, never a loop.
2. **Where the text comes from** (`GapTargets.select`, pure code in `scout`):
   - a `github` or `github-awesome` record: that repository's README;
   - an `hf-models`, `hf-trending` or `hf-provider-orgs` record: that model's card;
   - an `mcp-registry` record: its repository's README, only when the entry's namespace is `io.github.<owner>` and
     `<owner>` owns the repository. Anyone can publish an entry that links any repository; without this check a famous
     README would prove facts about someone else's server.
   - Links must match `https://github.com/<owner>/<repo>` or `https://huggingface.co/<owner>/<name>` exactly, so no
     link can reach another API path with our token.
3. **Reading** (port `ExtraEvidence`, adapter `sources/evidence/ReadmeEvidence`):
   - GitHub: `GET /repos/{owner}/{repo}/readme` (raw) and `GET /repos/{owner}/{repo}` for the licence GitHub states.
   - Hugging Face: `GET /{owner}/{name}/raw/main/README.md`; the licence is the `license:` line of the card's front matter.
   - Owner and name are URI template variables. At most 512 KB. Policy `collector-gap-evidence` (circuit breaker and
     retry, like a collector). A missing README is no text; any other failure leaves the first run as it is.
4. **Cleaning** (`ReadmeText`, before the model or the validator sees it): the first 64 KB only; zero-width, direction
   and tag characters removed first; Unicode NFKC; HTML comments, scripts, styles, tags, images, inline and reference
   link targets removed; email addresses replaced by `[email]`. Every pattern is bounded, so a hostile README cannot
   make cleaning slow. A
   window of 4000 characters keeps the top, then the licence, install and platform sections, then the rest.
5. **Second run.** The model gets the source records plus the README as one more `Material` with its own evidence id.
   The licence the GitHub API or the card states is a source fact, so a README that says something else is
   `CONFLICTING`. README text may support fields and relationships, never summary claims: a claim is the model's own
   sentence, and it could cite any true excerpt.
6. **Which run is kept.** The one with more accepted items (verified fields, relationships not rejected, claims). A gap
   run that keeps no more is dropped. One save per attempt.
7. **Fingerprint.** The saved run carries the fingerprint of the source records alone. The README is not stored, so the
   next run with unchanged records is `UNCHANGED`: the README is read once per fingerprint, and fields do not flip back
   and forth. The run stores which text it used in `enrichment_run.extra_evidence` (`github-readme:owner/repo`).
8. **Evidence.** A README excerpt is an `evidence` row with a link a person can open (`github.com/owner/repo#readme`,
   `huggingface.co/owner/model/blob/main/README.md`) and the read time, anchored to the record it
   was read for, so it goes when that record goes ([0035](../operations/0035-data-retention.md)). The README text
   itself is not stored: only the 20–300-character excerpts the validator accepted. The detail page names the source
   of such a quote: `owner/repo · README`, `owner/model · model card`.
9. **Cost.** `H2_SCOUT_GAP_DAILY_CALLS` (400) gap model calls a day, a share of the 500 enrichment calls
   (`H2_SCOUT_DAILY_ENRICHMENT_CALLS`, raised from 300 with this decision), not on top (both caps count them). At
   least 100 calls a day stay for first runs of new resources. A gap cap above the enrichment cap has no effect. Taken only when there is text to send; when none is left today, or with `0` (off), no README is read either. The per-run enrichment
   budget ([0020](0020-scout-runner.md) §6) counts resources, not gap calls: a gap call is part of its resource's
   attempt, so a run can make up to its budget plus the gap calls left today. Reading a README needs `H2_GITHUB_TOKEN`
   (without it GitHub allows 60 calls an hour); a model card needs no token. Gauge
   `helper2.scout.gap.budget.remaining`; counter `helper2.scout.gap` (source, outcome: `filled`, `not_better`,
   `no_text`, `source_failed`, `model_failed`, `skipped_cap`).

### Consequences
- Fewer unproven fields and fewer review items, at most 400 extra model calls a day, inside the 500.
- A resource is read again only when its source records change, or the prompt or model changes.
- The demo profile has no adapter, so it has no gap round.
- A README redirect to another host is refused and counts as a failure.

### Alternatives considered
- Let the model write new search queries for the collectors — the collectors read feeds and cursors, they take no
  query; and the model would choose what to fetch.
- Store the README — copies third-party text we do not need; the excerpts are enough.
- Fold a README hash into the fingerprint — needs a GitHub call on every run just to compute it.
- Run the gap round as a separate backlog step — two saves per resource, and a worse run could replace a better one.
- Trust any MCP Registry link — the entry's author chooses it.

### Code
- `backend/apps/backoffice/src/main/kotlin/ai/helper2/scout/GapRound.kt` — `GapTargets`, `ReadmeText`, ports `ExtraEvidence`, `GapBudget`.
- `backend/apps/backoffice/src/main/kotlin/ai/helper2/scout/usecase/EnrichResource.kt` — the gap round.
- `backend/apps/backoffice/src/main/kotlin/ai/helper2/scout/Enrichment.kt` — `Material.anchorRecordId`, `supportsClaims`; claims may not cite README text.
- `backend/apps/backoffice/src/main/java/ai/helper2/sources/evidence/ReadmeEvidence.java`; bean in `sources/config/SourcesConfiguration.java`.
- `backend/apps/backoffice/src/main/java/ai/helper2/scout/internal/CatalogEnrichmentStore.java` (gap target), `ScoutConfiguration.java` (budget).
- `backend/libs/catalog/src/main/java/ai/helper2/catalog/internal/JdbcEnrichmentCandidates.java` — each source text with its source id, external id
  and repository link (port `catalog.enrichment.EnrichmentCandidates`).
- `backend/libs/catalog/src/main/java/ai/helper2/catalog/internal/JdbcEnrichmentResults.java` — evidence with its own URL; `V2__enrichment_extra_evidence.sql`.
- `frontend/src/lib/evidenceSource.ts`, `frontend/src/components/EvidenceList.tsx`.
- Tests: `GapRoundTest`, `EnrichResourceGapRoundTest`, `ReadmeEvidenceTest`, `CatalogEnrichmentStoreIT`, `CatalogEnrichmentStoreTest`,
  `JdbcEnrichmentCandidatesIT`, `evidenceSource.test.ts`.

### Diagrams
[flow](../../diagrams/flow-0023-enrichment-gap-round.svg) · [sequence](../../diagrams/sequence-0023-enrichment-gap-round.svg)

### Roadmap
- [Gap round for resources enriched before it](../../roadmap.md#4-ai-quality-and-cost)
- [Lawyer review of README and model-card quotes](../../roadmap.md#product-and-legal)

<a id="part-0056"></a>

## Part: was ADR 0056 — Evidence ladder: every field from the records, the README, then the product's own pages

**Status.** Accepted. **Builds on:** [the part that was ADR 0019](#part-0019), [0006](../foundation/0006-outbound-calls-and-resilience.md), [0014](../search/0014-tavily-store-and-daily-quota.md), [0018](../guide/0018-ai-content-marking-and-research-trail.md), [the part that was ADR 0043](#part-0043), [0022](0022-discovery.md). **Amends:** [the part that was ADR 0019](#part-0019) (validator rules, prompt v4), [the part that was ADR 0043](#part-0043) (trigger, more steps). **Amended by:** [the part that was ADR 0062](#part-0062) (writing keeps only audience, tech level and use case; the re-check runs again for `enrich-v5`), [0026](../edge/0026-back-office-and-review.md) (a field an admin set is a stated fact; the model's value is dropped).

### Context
A product page on Helper2 shows pricing, platforms, licence, use cases, who it is for and its technical level. Most
of these come from enrichment ([the part that was ADR 0019](#part-0019)), and enrichment sees only what the sources
store: a title and a short excerpt. The gap round ([the part that was ADR 0043](#part-0043)) adds the README or model card,
but only for repositories and models, and only when a run ends `PARTIAL` or `REJECTED`. A product a person accepted
from discovery ([0022](0022-discovery.md)) is a web page: no README, and its price is on its pricing page.
So most product pages say "Not stated" for almost everything.

The goal is every field filled, with no hallucinations. Facts (price, licence, open source, platforms, type, use case,
relationships) need a quote that states them, or they stay "Not stated". Who a resource is for and how technical it is
are judgements: a model may estimate them, and the page says so. The description may be AI-written, labelled.

### Decision
**Names (6 Nov 2026).** The product calls the page step "page reader" (`page-reader`, `H2_SOURCE_PAGE_READER_*`, `H2_SCOUT_PAGE_READER_DAILY_CREDITS`). Tavily Extract is the adapter behind it (`sources/evidence/TavilyExtract.java`). Tavily's written OK for Extract arrived on 6 Nov 2026; the page step runs on prod with the default of 20 credits a day.

1. **When the ladder climbs.** After the first run, inside the same `EnrichResource` attempt, when a target fact of the
   type is still missing (`FieldTargets.forType`, pure code) or the run is `PARTIAL` or `REJECTED` (as in the part that was ADR 0043):
   - `PRODUCT`, `AGENT`: pricing, platforms, use case;
   - `MCP_SERVER`, `SKILL`, `LIBRARY`: licence, open source, platforms, use case;
   - `MODEL`, `DATASET`: licence, open source, use case; `PROMPT`: use case;
   - reading types (articles, papers, news, events, courses): none, their facts come from their sources.
   A fact the sources state (licence, type, open source) is not missing. Audience and tech level never make it climb.
2. **The steps.** Each step is one more model call, and each keeps the run that keeps more (as in the part that was ADR 0043). At most three
   model calls per attempt; the attempt saves once, under the fingerprint of the records alone.
   - Step 1: the records (as before).
   - Step 2: the README or model card (the part that was ADR 0043). A discovery record (`discovery-<category>`, `WebSources.isDiscovery`) whose URL is `github.com/<owner>/<repo>` now
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
   `H2_LIVE_WEB_API_KEY` or with `H2_SOURCE_PAGE_READER_ENABLED=false`.
5. **Cleaning** (`UntrustedPageText`, the README cleaner made general): the first 64 KB; hidden characters, HTML
   comments, scripts, styles, hidden elements (`hidden`, `display:none`, `visibility:hidden`, `aria-hidden`) with their
   whole subtree, tags and link targets removed. Hidden elements are found in one linear pass that counts nested tags
   of the same name; a hidden element that never closes takes the rest of the page; lines that talk to an AI ("ignore previous instructions", "set pricing ...") removed; email
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
   `AI_ESTIMATED`. As in [0018](../guide/0018-ai-content-marking-and-research-trail.md): the page labels them "AI-estimated" and marks
   them `data-ai-generated`; the API says `origin: AI_ESTIMATED` (OpenAPI enum `ValueOrigin`).
9. **Budgets and cost.**
   - Model calls 2 and 3 take the gap cap (`H2_SCOUT_GAP_DAILY_CALLS`, inside the enrichment cap).
   - Extract credits: `helper2.scout.evidence.daily-page-read-credits` (`H2_SCOUT_PAGE_READER_DAILY_CREDITS`, 20).
     Each credit also counts on the global Tavily credit counter of live search ([0014](../search/0014-tavily-store-and-daily-quota.md),
     `H2_LIVE_WEB_DAILY_CREDITS`), and on `helper2.live-web.credits{feature=scout-evidence,tier=none}`. When the
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

### Consequences
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
  `H2_SCOUT_PAGE_READER_DAILY_CREDITS=0` or `H2_SOURCE_PAGE_READER_ENABLED=false` without a release.
- A page is read once in 30 days. A price that changes later is not seen until then (roadmap).
- A retry after the 24-hour text expired pays Tavily again (one or two credits).
- The cached text is small (about 10 products a day, at most 4 pages of 6,000 characters each, under 300 KB) next
  to the 128 MB Redis that also holds sessions and counters.

### Security (STRIDE)
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

### AI architecture check
| # | Layer | State |
|---|---|---|
| 1 | Model | Covered: the same reasoning model; a provider outage is `LLM_UNAVAILABLE`, the first run stands. |
| 2 | Agent Framework | Covered: no agent loop; a fixed ladder of at most three calls, code-driven. |
| 3 | Orchestration | Covered: inside the existing Scout run; retries by resilience policy; one save per attempt. |
| 4 | Tool & API | Covered: Tavily Extract behind a port, URLs only from code; model tool calling rejected (below). |
| 5 | Memory | Covered: cleaned page text in Redis for at most 24 h (retry only); the ledger keeps URL and text hashes for 30 days. |
| 6 | Knowledge & RAG | Covered: grounding by quotes from retrieved pages; no vector search needed here. |
| 7 | Data | Covered: `V11` migration (origins, `summary_claim.self_declared`, `maintenance_task`); evidence anchored to its record (0035); a code re-check of stored answers. |
| 8 | Browser & Computer Use | Covered: none; Tavily reads the page, we never run a browser. |
| 9 | Guardrails | Covered: URL scope, cleaning, stating words, claim checks, caps on relationships, source facts win. |
| 10 | Observability | Covered: `helper2.scout.fields`, `helper2.scout.gap`, credits counter and gauge; no page text in logs. |
| 11 | Evaluation | Deferred: adversarial unit tests now; a fill-rate eval set is on the roadmap (needs real runs first). |
| 12 | Deployment | Covered: config only (`H2_SCOUT_EVIDENCE_*`, `H2_SOURCE_PAGE_READER_*`), on by default, off without a key. |
| 13 | Security & Identity | Covered: Tavily key from OpenBao as before; no new secret. |
| 14 | Human-in-the-Loop | Covered: self-declared relationships wait in the review queue; validation errors open review items. |
| 15 | Cost & Reliability | Covered: own credit cap, global Tavily cap, gap cap, 30-day ledger, circuit breaker. |

### Alternatives considered
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

### Code
- `backend/apps/backoffice/src/main/kotlin/ai/helper2/scout/OfficialPages.kt` — `FieldTargets`, `PageTargets`, `PageScope`, `OfficialLinks`, `OfficialPages`, ports `PageReader`, `ExtractBudget`, `PageLedger`, `PageTextCache`, `DomainRoots`.
- `backend/apps/backoffice/src/main/kotlin/ai/helper2/scout/usecase/RecheckEnrichments.kt` — the code re-check, port `StoredAnswers`; `scout/internal/CatalogStoredAnswers.java`, `catalog/enrichment/StoredEnrichments.java` (implemented by `JdbcEnrichmentResults`); `admin/web/AdminController.java`; `frontend/src/pages/admin/ScoutRunsPage.tsx`.
- `backend/apps/backoffice/src/main/java/ai/helper2/scout/internal/RedisPageTextCache.java` — the 24-hour cleaned text.
- `backend/apps/backoffice/src/main/kotlin/ai/helper2/scout/Enrichment.kt` — `FINGERPRINT_EPOCH`.
- `backend/apps/backoffice/src/main/kotlin/ai/helper2/scout/EvidenceValidator.kt` — stating words, `PricingWords`, `ClaimCheck`, judgement fields, self-declared rules.
- `backend/apps/backoffice/src/main/kotlin/ai/helper2/scout/usecase/EnrichResource.kt` — the ladder, `helper2.scout.fields`.
- `backend/apps/backoffice/src/main/kotlin/ai/helper2/scout/GapRound.kt` — `UntrustedPageText`, `GapKind.OFFICIAL_PAGE`.
- `backend/libs/kernel/src/main/kotlin/ai/helper2/shared/Vocabulary.kt` — `ValueOrigin.AI_ESTIMATED`, `SELF_DECLARED`.
- `backend/apps/backoffice/src/main/java/ai/helper2/sources/evidence/TavilyExtract.java`, `PublicSuffixDomains.java`; beans in `sources/config/SourcesConfiguration.java`.
- `backend/apps/backoffice/src/main/java/ai/helper2/scout/internal/ScoutConfiguration.java` (`officialPages`), `PageReaderCreditBudget.java`, `SharedCachePageLedger.java`, `CatalogEnrichmentStore.java` (type, page target), `SpringAiEnrichmentModel.java` (`enrich-v4`, material kind).
- `backend/apps/backoffice/src/main/resources/prompts/scout/enrich-v5.txt`, `db/migration/V11__evidence_ladder_origins.sql`.
- `backend/libs/catalog/src/main/java/ai/helper2/catalog/internal/JdbcEnrichmentResults.java` (origins), `JdbcEnrichmentCandidates.java` (accepted products first).
- `api/common.yaml, api/public.yaml` — `ValueOrigin`, `Relationship.origin`, `SummaryClaim.selfDeclared`.
- `frontend/src/components/results/FieldStates.tsx`, `frontend/src/pages/ResourcePage.tsx`, `frontend/src/lib/aiMark.ts`.
- Tests: `FieldTargetsTest`, `EvidenceValidatorLadderTest`, `OfficialPagesTest`, `EnrichResourceLadderTest` (adversarial pages),
  `TavilyExtractTest`, `PublicSuffixDomainsTest`, `PageReaderCreditBudgetTest`, `RecheckEnrichmentsTest`, `RedisPageTextCacheIT`,
  `ScoutPropertiesBindingTest`, `CatalogEnrichmentStoreIT`, `SecurityIT`, `EnrichResourceTest`, `ResourcePage.test.tsx`, `Admin.test.tsx`.

### Diagrams
[flow](../../diagrams/flow-0023-evidence-ladder.svg) · [sequence](../../diagrams/sequence-0023-evidence-ladder.svg) · [architecture: index](../../diagrams/architecture-index.svg)

### Roadmap
- [Evidence ladder follow-ups](../../roadmap.md#4-ai-quality-and-cost): site search (PR E), re-read pages after N days, a fill-rate eval set.
- [Lawyer review of product-page quotes and Tavily Extract](../../roadmap.md#product-and-legal)

<a id="part-0062"></a>

## Part: was ADR 0062 — Writing about a resource: an article keeps its own facts and names what it is about

**Status.** Accepted. **Builds on:** [the part that was ADR 0019](#part-0019), [0022](0022-discovery.md), [the part that was ADR 0056](#part-0056), [0021](0021-collectors-and-events.md) **Amended by:** [0026](../edge/0026-back-office-and-review.md) (an admin links, adds or renames the target in the review list).

### Context
Much of what the Scout finds is writing about other things: a news item about a new model, a blog post about a
product launch, a paper about a method. The enrichment did not know this. It asked the same questions of every
resource, as if it were a product.

So an article got the facts of what it talks about. An Engadget news item about "Gemini 4 Carbon" came back with
pricing FREEMIUM, audience BUILD_WITH_AI, use case coding, and type MODEL (refused, as a conflict with the source).
The pricing is Gemini's, not the article's. The type MODEL is not wrong either: the article is *about* a model. But
there was no place to say that.

Tavily discovery dropped the same writing on the way in ([0022](0022-discovery.md)): a blog post or a news
post about a product was neither a product site nor a list, so it was thrown away, with what it said about the
product.

### Decision
1. **Writing is ARTICLE, NEWS and PAPER** (`FieldsByType`). It keeps three fields: `audience` and `techLevel` (who it
   is for) and `useCase` (its topic). Pricing, licence, open source, platforms and type are facts of what it is
   about: the validator refuses them for writing ("not a fact of a NEWS, but of what it is about"), whatever the
   quote says. Every other type keeps every field.
2. **ABOUT links writing to its subject.** A new relationship kind, from the article to the model, product or tool it
   is mainly about. It follows the rules of every relationship ([the part that was ADR 0019](#part-0019)):
   - the quote must name the target;
   - a target the catalog has by that exact title is linked and VERIFIED;
   - an unknown target stays UNVERIFIED and opens a `RELATIONSHIP_CANDIDATE` review item;
   - at most 3 per run (`MAX_ABOUT`): an article is mainly about a few things, not every name it drops;
   - ABOUT is only for writing, and writing has no other kind.
3. **The model is told the type.** Prompt `enrich-v5`: the user message has `Type: NEWS` next to the title, and the
   rules say what writing gives and that it names its subjects with ABOUT. The type comes from the catalog
   (`EnrichmentInput.type`), never from the model.
4. **Stored answers are cleaned by code.** The re-check of [the part that was ADR 0056](#part-0056) runs once more after the
   deploy (marker `recheck-enrichment-enrich-v5`) and on the admin's button. It now also rejects every AI field
   that writing does not keep, whatever its verification. No model call: the rule is code. Writing gets its ABOUT
   links when it is next enriched (new or changed text, or an admin requeue), not by a rerun of the whole catalog.
5. **Discovery keeps posts about products as ARTICLE candidates** (`DiscoveryPages`, shape ARTICLE). A post is a page:
   - below a writing place (`/blog/…`, `/news/…`, `/posts/…`, `/insights/…`), or on a writing host (`blog.`,
     `news.`, Medium, Substack, TechCrunch …; the admin keeps the writing hosts and the directories,
     [0022](0022-discovery.md));
   - or with a post's slug (`/google-gemini-carbon-promises-significant-leap`).

   Not a post: a blog's index, an author's page (`medium.com/@someone`), docs, help, careers, legal and community
   pages, and directories (Product Hunt, G2, Futurepedia …). A post waits in the review list as a `RESOURCE_CANDIDATE`
   with type ARTICLE and its own URL; every discovery category declares ARTICLE, so it can be accepted. After that, its
   enrichment names what it is about.

### Consequences
- An article shows what it is about ("About Gemini 4 Carbon"), not the subject's price or licence.
- The Engadget item loses its pricing and type at the first re-check; its use case and audience stay.
- More review work: discovery now proposes posts too. Each one is a candidate once per URL, as before.
- An ABOUT target that is not in the catalog waits in review by name. The admin links it to a resource, adds the
  resource, or renames it ([0026](../edge/0026-back-office-and-review.md)).
- No change of cost: the type and two lines of rules add a few tokens per call, inside the same daily caps.
- No new personal data: the relationship holds a product or model name. The Privacy page does not change.
- AI Act Art. 50: an ABOUT link is AI-extracted like every relationship, and labelled the same way in the UI and the
  API (`origin`).

### Security and privacy (STRIDE)
- **Tampering / prompt injection**: an article can name any product to get a link. Mitigation: the quote must name
  the target, the target must already be in the catalog by exact title to be VERIFIED, at most 3 per run, the rest
  waits for a person.
- **Information disclosure**: none new; the target is a name the public article states.
- **Denial of service / cost**: no new calls; discovery posts are review items, capped per URL.
- **Elevation**: unchanged; only an admin accepts a candidate or a relationship.

### Alternatives considered
- **A `subject_id` column on the resource** — one subject only; a post often compares two models.
- **A separate mention table** — the same data as a relationship, with its own retention, API and review flow.
- **Let the model change the type** — the source states the type; a news item about a model is still news.
- **Create a resource for an unknown ABOUT target** — a resource needs its own URL, and the article gives only a
  name. The target waits in review by name; the post itself is the candidate, under its own URL.
- **Keep dropping posts in discovery** — they are often the first trace of a new product; the link to the subject is
  the value.

### Code
- `backend/apps/backoffice/src/main/kotlin/ai/helper2/scout/FieldsByType.kt` — writing types, their fields, ABOUT.
- `backend/apps/backoffice/src/main/kotlin/ai/helper2/scout/EvidenceValidator.kt` — fields and kinds by type, the ABOUT cap.
- `backend/apps/backoffice/src/main/kotlin/ai/helper2/scout/Enrichment.kt` (`EnrichmentInput.type`),
  `backend/apps/backoffice/src/main/java/ai/helper2/scout/internal/CatalogEnrichmentStore.java`.
- `backend/apps/backoffice/src/main/resources/prompts/scout/enrich-v5.txt`,
  `backend/apps/backoffice/src/main/java/ai/helper2/scout/internal/SpringAiEnrichmentModel.java`.
- `backend/apps/backoffice/src/main/kotlin/ai/helper2/scout/usecase/RecheckEnrichments.kt`,
  `backend/libs/catalog/src/main/java/ai/helper2/catalog/internal/JdbcEnrichmentResults.java` (the stored type).
- `backend/apps/backoffice/src/main/java/ai/helper2/sources/tavily/DiscoveryPages.java`, `TavilyDiscoveryCollector.java`.
- `backend/libs/schema/src/main/resources/db/migration/V16__relationship_about.sql`; `api/public.yaml` (kind ABOUT).
- Tests: `EvidenceValidatorWritingTest`, `RecheckEnrichmentsTest`, `SpringAiEnrichmentModelTest`,
  `DiscoveryPagesTest`, `TavilyDiscoveryCollectorTest`.
- Diagrams: [flow](../../diagrams/flow-0023-writing-about-a-resource.svg) · [sequence](../../diagrams/sequence-0023-writing-about-a-resource.svg).
