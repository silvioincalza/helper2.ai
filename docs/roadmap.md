# Roadmap

Every open item lives here, so open work is tracked in one place. ADRs link to this page
from their "Roadmap" section. The code has no TODO or FIXME comments.

The baseline is v1.0.0, the first release (27 Oct 2026); the current release is v1.0.1; the next is v1.0.2.
Items are grouped by the order in which they matter. Inside a group, the first item is the most useful.

## 1. Before the hackathon deadline (30 Oct 2026, 18:00 Zurich)

- **Full vector index before recording the video.** Every resource has an embedding
  ([ADR 0012](adr/search/0012-vector-search-pgvector.md)). The Scout fills the index over a few days (300 enrichments
  per day).

## 2. Launch hardening

### Product and legal
- Imprint and contact (EU). The Privacy and Terms pages exist; the Sources page lists attributions.
- Lawyer review of the Privacy and Terms pages and of the AI Act reading in
  [ADR 0038](adr/guide/0038-ai-content-marking.md): provider role, metadata-only marking, deadline.
- Lawyer review of the web-search quota ([ADR 0037](adr/search/0037-tavily-store-and-daily-quota.md)): whether an HMAC of
  the address or of the query is personal data, legitimate interest as the basis, and the wording on the transfer of
  queries to Tavily in the US.
- Check each US source and GitHub for Data Privacy Framework certification, as the Privacy page states.
- Make server and Traefik logs delete after 3 months, as the Privacy page states.
- Review each source's terms again for commercial use.
- SEO: `robots.txt`, `sitemap.xml`, metadata and JSON-LD for detail pages. Detail pages are not crawlable
  today, because the frontend is a single-page app ([ADR 0025](adr/frontend/0025-frontend-one-box.md)).

### Domain and identity
- DNS: `CAA` record for the edge's certificate authority; `v=spf1 -all` and DMARC `p=reject` if no mail is sent.
- Register a production GitHub OAuth App, add profile `oauth-github`, set `H2_GITHUB_OAUTH_CLIENT_ID`,
  `H2_GITHUB_OAUTH_CLIENT_SECRET` and `H2_ADMINS=github:<numeric id>` ([ADR 0023](adr/edge/0023-identity-and-admin.md)).
- ADMIN is decided at sign-in, so a change to `H2_ADMINS` needs a new sign-in.
- Optional `H2_HF_TOKEN` on prod.
- **Sign-in for everyone.** Sign-in is offered only on `/admin`, so server-side bookmarks and account deletion
  (`DELETE /me`) reach only admins today. Add a public sign-in entry point in the page; it needs the
  `oauth-github` profile on prod ([ADR 0023](adr/edge/0023-identity-and-admin.md)).
- Turn on the registered Tavily tier (more web searches per day for signed-in users) once that sign-in is live
  ([ADR 0037](adr/search/0037-tavily-store-and-daily-quota.md)).
- Admin back office on `admin.helper2.ai`, reachable only through WireGuard via Traefik, with no app-level
  network filter. Not built yet: today `/admin` is served on the public hosts and protected only by sign-in
  ([ADR 0023](adr/edge/0023-identity-and-admin.md)).

### Quality gates
- **Browser end-to-end tests (skipped for now, owner decision 27 Oct 2026).** No browser journey is automated,
  and no tool is installed. Later, choose the tool, then on the `demo` profile:
  search with a partial result and the source status panel; ask the guide, follow the streamed steps, close the
  answer and keep the search; back and forward; the theme switch. Then run them in CI.
  See [user-journeys.md](user-journeys.md) (SC-1 to SC-3).
- Manual accessibility pass on real browsers: keyboard, screen reader, contrast, mobile.
  - After a route change, the focused heading and the live region can both be read out.
- Load test of search at the expected peak: confirm p95 and that the rate limits do not trip for normal use.
- Run `./gradlew liveTest` against production egress.
- Ranking measured on a labeled query set ([ADR 0009](adr/search/0009-ranking.md)). The harness and the queries exist
  ([ADR 0036](adr/quality/0036-search-evaluation-harness.md)); the grades do not. Label them as
  [docs/eval](eval/README.md) describes, then record the baseline.
- Guide relevance judged by a second person, not only by the owner ([ADR 0030](adr/quality/0030-guide-evaluation.md)).

## 3. Operations

- **Exporters.** Postgres and Redis exporters in the Helm chart, and an alert on Redis evicted keys
  (`allkeys-lru` can drop sessions, rate-limit windows and web-search counters under memory pressure).
- **Backups:**
  - test a restore;
  - add a periodic restore-check job;
  - turn on the off-site copy to R2 (`backup.offsite`) with a bucket-scoped token;
  - encrypt dumps on the client side before upload ([ADR 0027](adr/operations/0027-runtime-topology.md)).
- **Short-lived deployer token.** Mint it with the OpenBao Kubernetes secrets engine. Today the deployer token
  in `deploy/k8s/terraform/rbac.tf` does not expire ([ADR 0028](adr/operations/0028-ci-cd.md)).
- **Supply chain:**
  - pin base images by digest (`deploy/Dockerfile`, `deploy/postgres/Dockerfile`);
  - set `postgres.image.digest`;
  - add an image scan and an SBOM.
- Real Terraform validation of the edge module needs read access to the private `topology` submodule.
  CI uses a stub today.
- cloudflared egress allows `0.0.0.0/0:7844`. Narrow it to Cloudflare's published ranges.
- Redis with `redis.auth.existingSecret` has no restart checksum, so a password rotation needs a manual restart.
- Remove the unused duplicate `deploy/.dockerignore`.
- The Cloudflare tunnel token lives in Terraform state and a Kubernetes Secret, not in OpenBao. Move it.
- Confirm that trace and span ids appear in every log line (only a configuration comment says so today).

## 4. More than one replica

Prod runs 3 replicas (`app.replicas`, staging 1). The Scout and the embedding indexer run on one replica at a
time (ShedLock, table `shedlock` in Postgres); rate limits and sign-in sessions are in Redis. Still per replica,
on purpose:

- **Guide answers: in-memory LRU and single-flight map.** Answers are also in Redis, so a replica only misses a
  question it has not seen; two replicas may answer the same new question once each.
- **Query-vector cache** (`SemanticSearchSource`). A miss costs one embedding call (about 0.3 s) on that replica.
- **Source cooldowns after a rate limit** (`SearchResources`), **circuit breakers and bulkheads** (`source-<id>`
  policies, [ADR 0033](adr/foundation/0033-resilience-policies.md)) and the Sources page's last outcomes (`SourceHealth`).
  Each replica finds a limited or failing source on its own, at the cost of a few more refused calls.
- **Query-translation cache** (`SpringAiQueryTranslator`). A miss costs one model call, inside the daily
  translation cap, which is counted in Redis.
- **Guide concurrency limit** (`H2_GUIDE_MAX_CONCURRENT`, 8 per replica, 24 on prod). The daily model-call cap
  in Redis still bounds the cost.
- **Embedding indexer pause after a rate limit.** Only the replica that got the 429 pauses; the lock already
  keeps the cluster at one batch per period.

Open items:

- **preStop delay.** A pod gets SIGTERM while it is still being removed from the Service endpoints; a few
  seconds of `preStop` sleep would stop the last requests from reaching it during a rolling update.
- **Redis memory.** Sign-in sessions, rate-limit windows and web-search counters share the 128 MB `allkeys-lru`
  cache: under pressure an admin is signed out, or a client gets a fresh window or a fresh day early. Alert on
  Redis evictions (with the Redis exporter, section 3).

## 5. AI quality and cost

- The guide reason check is a model judgment and can miss a claim. Only the quote is proven
  ([ADR 0016](adr/guide/0016-guide-reason-check.md)).
- Watch whether the source `type`, now a source fact in enrichment, sends many proposals to CONFLICTING review
  (for example GitHub LIBRARY versus MCP_SERVER) ([ADR 0019](adr/scout/0019-evidence-gated-enrichment.md)).
- Cross-language retrieval in vector search.
- A Grafana panel for the share of kept picks ranked 9+ (`helper2.guide.picks{rank="9+"}`), next to the alert
  `Helper2GuidePicksFromLowRanks` ([ADR 0015](adr/guide/0015-ai-guide-evidence-gate.md)).
- Show the web searches left today on the page, and say when the daily limit is reached (FE-2,
  [ADR 0037](adr/search/0037-tavily-store-and-daily-quota.md)). The API already returns them (`webSearches`).
- Write the Scout's Tavily news and events to the same stored sets as searches, with their own share of credits
  ([ADR 0037](adr/search/0037-tavily-store-and-daily-quota.md)).
- Setup guide: a model answer that stays unreadable after two attempts is not cached, so the same resource and
  question cost two model calls on every request until the daily cap. Give it its own reason and a short negative
  cache ([ADR 0031](adr/guide/0031-setup-guide.md)).
- **Signed provenance manifest for AI-written text** (C2PA-style): sign each guide answer with its model, time and
  AI-written fields, so the origin survives outside our page and API. Today the marking is metadata only and is
  lost on copy ([ADR 0038](adr/guide/0038-ai-content-marking.md)). Do it when a standard and tooling for text exist, or
  if a regulator finds metadata alone too weak.
- Pass a prompt version from `SpringAiEnrichmentModel`, `SpringAiToolGuideModel` and `SpringAiRepoClassifier`, and
  put them under the `llm-chat` circuit; then delete the "unversioned" methods of `ModelCallMetrics`
  ([ADR 0014](adr/guide/0014-llm-integration.md)).
- Guide `WHEN` for a release: when our sources have no creation date, take the date quoted in a Tavily news item,
  through the evidence gate. Measure after the `WHEN` event filter ([ADR 0032](adr/guide/0032-guide-intents.md)).
- Run `scripts/eval-guide.mjs --adversarial` in a scheduled job after each deploy; today it is run by hand.
- A question from a shared `?ask=` link is asked on page load. Asking only after a click would stop a link from
  spending model calls for its visitors (one cached answer per question limits it today).

## 6. Search and catalog

- `ListRecentResources` and `ListUpcomingEvents` make two queries per item, and the evidence loaders make one
  query per assertion. Batch them.
- Indexed resources left without source records are not deleted (only observed ones are).
- The Apache client's response timeout applies per read, not as a total deadline per call
  ([ADR 0006](adr/foundation/0006-outbound-http.md)).
- The embedding backlog gauge does not count rows whose text changed after they were embedded.
- Conference files that the ai-deadlines collector skips are logged, not counted in a metric.
- On a resource's first search, the discovery date is unknown, because catalog writes run off the request path
  ([ADR 0004](adr/foundation/0004-persistence-and-provenance.md)).
- "Works with" flags on index hits name `helper2-index` as their source, not the source that set the tag, and only
  the first 20 tags (alphabetical) are kept, which can drop a flag ([ADR 0013](adr/search/0013-works-with-flags.md)).
- Guide recommendations carry no "works with" flags (they are not built with `CardMapper.card()`).
- The MCP Registry asks for hourly polling, but the Scout runs once a day in the cluster, so the registry is
  read daily ([ADR 0018](adr/scout/0018-scout-runner.md)).
- More registries, if their terms allow it. Consumer AI-tool coverage is thin.
- Live event collectors for hackathons, meetups, talks and workshops, so the curated community-events list
  ([ADR 0022](adr/scout/0022-next-events.md)) does not need a research pass to stay current. Candidates: Devpost and
  MLH (hackathons), Luma and Meetup (meetups, talks, workshops), Eventbrite. Each needs a terms review for public
  redisplay first, must state the days and the format (online, in person) in a form code can read, and Meetup's
  API needs a paid plan.
- An admin UI for the curated list.
- Source requests: anyone can ask for a new source of information (a registry, an e-learning platform, a vendor's
  catalog, a newsletter) from the Sources page. A request names the site and what it lists; it goes to the admin
  review list, and an accepted request becomes a collector or a curated entry after a terms review for public
  redisplay. Rate-limited like issue reports; no sign-in needed. Courses and certifications (Coursera, O'Reilly,
  edX, Udemy, vendor programmes) are the first case ([ADR 0032](adr/guide/0032-guide-intents.md)).

## 7. Frontend

- **Saved** (bookmarks) is hidden until it is finished: the menu item, the Save buttons and `/saved` (which goes
  home). The bookmark code, its tests and the `/me/bookmarks` API stay; `SAVED_ENABLED` in
  `frontend/src/lib/features.ts` brings it back.
- The shared `/sources` data is fetched once per page load, so the Sources page can show an old last outcome
  during a long visit.
- If browser storage is blocked, a fixed theme choice can be overridden when the device theme changes.
- The guide answer shows only its own notice (`GUIDE_DAILY_QUOTA`), not the search notices such as
  `CACHE_UNAVAILABLE`.
- More UI languages. The UI is English only; queries work in any language.
- TypeScript 7 and ESLint 10 (with `@eslint/js` 10) are held back in `.github/dependabot.yml`: on 27 Oct 2026
  `typescript-eslint` 8.72 needs TypeScript <6.1 and `eslint-plugin-jsx-a11y` 6.10 needs ESLint <=9. Remove the
  ignore rules once both plugins support the new majors.

- **Design v2** ([0040](adr/frontend/0040-frontend-design-v2.md)) is done and on staging. Prod gets it with the next release:
  cut one once staging looks right.
- The News and Events boards move on their own. Hover, focus and tap pause them; check this against WCAG 2.2.2 with
  a real touch and screen-reader test before launch.
- Tracking cookies are planned. Before the first one: consent banner, cookie notice, Privacy page and record of
  processing, all in the same pull request, wording checked by a lawyer.

## 8. Architecture

- The Scout reads catalog tables (`resource`, `source_record`) for its enrichment backlog in
  `JdbcEnrichmentStore`. Spring Modulith cannot see coupling through the schema; read them through a catalog port
  ([ADR 0002](adr/foundation/0002-modular-monolith-hexagonal.md)).
- `platform` is an open module and holds the LLM layer. Consider a closed `llm` module with a named interface.
- Move the source and catalog adapters into `internal` packages.

## 9. Documentation

- `docs/diagrams/software-architecture.svg` is about 5,800 px wide: readable at full size, too small when scaled
  down. Split it into one diagram per module group.
- `docs/diagrams/flow-0025-frontend-one-box.svg` has two crossing dashed edges near `HomePage`.

## 10. Known limits

What each decision does not cover. These come from the ADRs, which describe only what the code does. Fix an item
here or accept it; when it is fixed, remove it.

### Foundation

- Startup is slower than a native binary: Flyway and the language models load first. The Helm chart gives the startup probe up to 300 seconds (see 0027). ([ADR 0001](adr/foundation/0001-stack-and-runtime.md))
- Two backend languages raise the entry cost for a new developer. 0002 limits Kotlin to the business rules. ([ADR 0001](adr/foundation/0001-stack-and-runtime.md))
- The SPA is not crawled well by search engines; detail pages have no HTML rendered on the server. ([ADR 0001](adr/foundation/0001-stack-and-runtime.md))
- `setup` has no `usecase` package yet: its split waits for the tool-guide work in that module. ([ADR 0002](adr/foundation/0002-modular-monolith-hexagonal.md))
- The `guide` domain uses `platform.llm.CallCounter` and `DailyBudget`. Allowed because `platform` is open, but the LLM layer is visible to every module. ([ADR 0002](adr/foundation/0002-modular-monolith-hexagonal.md))
- Modulith checks Java types only. The `scout` module writes enrichment tables that `catalog` reads; this coupling through the schema is not checked. ([ADR 0002](adr/foundation/0002-modular-monolith-hexagonal.md))
- Generated code is not committed. After a fresh checkout, run Gradle or npm once before the editor can resolve the generated types. ([ADR 0003](adr/foundation/0003-contract-first-rest-api.md))
- The SSE handler is not generated, so the compiler ties only its event names to the contract. ([ADR 0003](adr/foundation/0003-contract-first-rest-api.md))
- A resource seen for the first time has no discovery date in that first response: the write is still in the queue. ([ADR 0004](adr/foundation/0004-persistence-and-provenance.md))
- On shutdown the queue gets 5 seconds to finish; later writes are lost. ([ADR 0004](adr/foundation/0004-persistence-and-provenance.md))
- Bookmarks and reports are user data; the backup (0027) is the only copy. ([ADR 0004](adr/foundation/0004-persistence-and-provenance.md))
- During a Redis outage, budgets count in memory per replica: with 3 replicas, up to three times what was left of the day. A restart during an outage resets that replica's count. ([ADR 0005](adr/foundation/0005-redis-disposable-cache.md))
- With `everysec`, a Redis crash loses at most the last second of writes; a lost volume starts Redis empty. ([ADR 0005](adr/foundation/0005-redis-disposable-cache.md))
- Redis holds sessions, rate-limit windows and web-search counters next to cache entries, in the same 128 MB with `allkeys-lru`: under memory pressure an admin can be signed out, or a client can get a fresh window or day early. ([ADR 0005](adr/foundation/0005-redis-disposable-cache.md))
- arXiv: while Redis is down, arXiv is not called. ([ADR 0006](adr/foundation/0006-outbound-http.md))
- The response timeout applies to each read, not to the whole call. A Scout collector has no deadline of its own. ([ADR 0006](adr/foundation/0006-outbound-http.md))
- The Kubernetes Secret is only base64 in the cluster; access to the namespace means access to it. ([ADR 0007](adr/foundation/0007-configuration-and-secrets.md))
- The Cloudflare tunnel token is created by Terraform and kept in its state and in the Secret `cloudflared-token`, not in OpenBao. ([ADR 0007](adr/foundation/0007-configuration-and-secrets.md))
- A renamed variable needs the OpenBao entry rewritten by hand; an old name is silently ignored. ([ADR 0007](adr/foundation/0007-configuration-and-secrets.md))
- Circuits live in each replica's memory; each replica finds an outage on its own (10 calls at most). ([ADR 0033](adr/foundation/0033-resilience-policies.md))

### Search

- The response waits for the slowest answering source up to its deadline: Tavily (6 s) sets the worst case; a non-English query can add the translation wait (0011). ([ADR 0008](adr/search/0008-federated-search.md))
- Cooldowns, circuits and per-source health on the Sources page live in each replica's memory; the Sources page shows what the answering replica has seen. ([ADR 0008](adr/search/0008-federated-search.md))
- Facets and filters only see the hits fetched for this query (at most 30 per source), not the whole catalog (also 0010). ([ADR 0008](adr/search/0008-federated-search.md))
- Ranking weights and popularity references are chosen by hand from a few queries; no labeled query set measures them. ([ADR 0009](adr/search/0009-ranking.md))
- The popularity formula exists twice, in SQL (`h2_resource_popularity`) and Kotlin (`Ranking.popularity`); they must change together. ([ADR 0009](adr/search/0009-ranking.md))
- Index stage 1 can cut rows that hold only some words when more than 300 rows match. ([ADR 0009](adr/search/0009-ranking.md))
- The timeline pages 50 results at a time, so one month can be split across two pages. ([ADR 0010](adr/search/0010-filters-facets-dates-timeline.md))
- A wrong early date from one source moves a result in sort and timeline; the badge shows the date is disputed. ([ADR 0010](adr/search/0010-filters-facets-dates-timeline.md))
- The translation cache is per replica; a miss on another replica costs one more model call. ([ADR 0011](adr/search/0011-language-aware-search.md))
- The embedding backlog gauge does not count rows whose text changed after they were embedded. ([ADR 0012](adr/search/0012-vector-search-pgvector.md))
- The 0.65 semantic cutoff comes from one set of measurements and may need tuning. ([ADR 0012](adr/search/0012-vector-search-pgvector.md))
- Query-vector cache and the 2 min rate-limit pause are per replica. ([ADR 0012](adr/search/0012-vector-search-pgvector.md))
- "Works with" tag lists are kept by hand; a resource with no tags gets no flag; index hits name `helper2-index`, not the original source; the 20-tag limit can drop a tag. ([ADR 0013](adr/search/0013-works-with-flags.md))
- Accepted risk (owner, 30 Oct 2026): rotating addresses can drain the monthly Tavily credits while no cap is set; then searches answer from stored sets and the other sources. ([ADR 0037](adr/search/0037-tavily-store-and-daily-quota.md))
- Under Redis memory pressure a web-search counter can be evicted and a visitor's day starts early. ([ADR 0037](adr/search/0037-tavily-store-and-daily-quota.md))
- The quota is taken before the call, so a call the circuit then refuses still counts for the visitor. ([ADR 0037](adr/search/0037-tavily-store-and-daily-quota.md))
- A stored hit is read back with two short queries per hit (owner, then records). ([ADR 0037](adr/search/0037-tavily-store-and-daily-quota.md))
- A scheduled Scout run can start up to one `H2_SCOUT_TICK` (1 h) after it is due; a short `H2_SCOUT_EVERY` needs a shorter tick. ([ADR 0018](adr/scout/0018-scout-runner.md))
- A pause of the lease-renewal thread longer than one lease lets another replica start a second run. ([ADR 0018](adr/scout/0018-scout-runner.md))
- The enrichment review queue grows with every partial run and needs a person. ([ADR 0019](adr/scout/0019-evidence-gated-enrichment.md))
- HF provider models reach no enrichment: the list API returns no description. ([ADR 0020](adr/scout/0020-scout-collectors.md))
- Coverage of consumer AI tools is thin: sources lean toward developer resources. ([ADR 0020](adr/scout/0020-scout-collectors.md))
- Course pages wait for an admin decision each week. ([ADR 0020](adr/scout/0020-scout-collectors.md))
- An event whose source gives no day is not listed; curated events need a research pass to stay current. ([ADR 0022](adr/scout/0022-next-events.md))

### AI guide and quality

- Setting `H2_LLM_MAX_RETRIES` above 0 adds attempts that no budget counts; the LLM circuit lives in each replica's memory. ([ADR 0014](adr/guide/0014-llm-integration.md))
- Thinking off makes calls faster and repeatable, but the chooser returns fewer picks (0030). ([ADR 0014](adr/guide/0014-llm-integration.md))
- The gate proves the quote, not that it is true: a description written to be chosen passes the gate and the reason check. ([ADR 0015](adr/guide/0015-ai-guide-evidence-gate.md))
- The guide is only as good as retrieval: a resource not in the 24 candidates cannot be picked. ([ADR 0015](adr/guide/0015-ai-guide-evidence-gate.md))
- The guide SSE contract is hand-written, not generated from OpenAPI. ([ADR 0015](adr/guide/0015-ai-guide-evidence-gate.md))
- The reason check is a model's judgment and can miss a claim or reject a correct one; it adds one model call per question (the 600-call daily cap covers at most 200 fresh questions). ([ADR 0016](adr/guide/0016-guide-reason-check.md))
- Clients behind one shared address (office, mobile carrier) share one guide quota. ([ADR 0017](adr/guide/0017-guide-cost-guard.md))
- The in-memory answer cache, single-flight map and `guide` bulkhead are per replica; while Redis is down each replica counts on its own. ([ADR 0017](adr/guide/0017-guide-cost-guard.md))
- The quota is taken before the question runs, so a question that later fails still counts. ([ADR 0017](adr/guide/0017-guide-cost-guard.md))
- 20 evaluation questions is a small sample; the script measures the JSON endpoint, not the stream or the browser; an ungraded pick counts as not relevant. ([ADR 0030](adr/quality/0030-guide-evaluation.md))
- Past the hourly fetch budget, an uncached resource gets no README snippet and no steps until the next hour; client formats change and need a re-check. ([ADR 0031](adr/guide/0031-setup-guide.md))
- A name is asked back only when our sources hold entries of two kinds; an unrelated project with a model's exact name also triggers the question. ([ADR 0032](adr/guide/0032-guide-intents.md))
- A broad "what is new" question takes about 10 s longer (thinking on for its choose call); a subject slower than 10 s is reported as not found. ([ADR 0032](adr/guide/0032-guide-intents.md))
- `NEWEST` drops undated results (npm and others) silently. ([ADR 0032](adr/guide/0032-guide-intents.md))
- Events are found only in a source's own top hits; event words are a fixed list. ([ADR 0032](adr/guide/0032-guide-intents.md))
- The course list needs editorial review; Coursera, edX and Udemy are covered only through the collector and the seed. ([ADR 0032](adr/guide/0032-guide-intents.md))
- Frozen snapshots age; pooled judging leaves results outside every top 20 unjudged; labelling is about 5 hours of human work per judge. ([ADR 0036](adr/quality/0036-search-evaluation-harness.md))
- Lawyer: whether metadata-only marking is enough for text (it is lost on copy). ([ADR 0038](adr/guide/0038-ai-content-marking.md))
- A new AI-written field must be added to the marking and its tests by hand. ([ADR 0038](adr/guide/0038-ai-content-marking.md))

### Edge, frontend and operations

- Sign-in is offered only on `/admin`, so visitors keep bookmarks only in their own browser. ([ADR 0023](adr/edge/0023-identity-and-admin.md))
- `/admin` and `/api/v1/admin/**` are on the public host, protected only by the role check. ([ADR 0023](adr/edge/0023-identity-and-admin.md))
- Roles live in the session: removing an admin takes effect at their next sign-in; a Redis outage or eviction signs the admin out. ([ADR 0023](adr/edge/0023-identity-and-admin.md))
- While Redis is down each replica counts alone, so a client can get up to 3 times the rate limit on prod; an evicted key gives a fresh window early. ([ADR 0024](adr/edge/0024-http-edge-protection.md))
- Clients behind one shared address (office, carrier NAT) share one rate limit. ([ADR 0024](adr/edge/0024-http-edge-protection.md))
- No browser end-to-end tests: frontend tests run in jsdom only, also in CI (0028). ([ADR 0025](adr/frontend/0025-frontend-one-box.md))
- A recent answer can be up to 5 minutes old; a new page that shows data the user changes must pass `cache: false`. ([ADR 0039](adr/frontend/0039-frontend-recent-answers-cache.md))
- The picks bar takes about 110 px above the list, also when the guide has nothing to say. ([ADR 0040](adr/frontend/0040-frontend-design-v2.md))
- Two result layouts (desktop preview, inline preview on a phone) to build and test. ([ADR 0040](adr/frontend/0040-frontend-design-v2.md))
- The picks bar says only that the guide is reading (no step-by-step progress); there is no button to close the guide's answer. ([ADR 0040](adr/frontend/0040-frontend-design-v2.md))
- Alert delivery (Alertmanager receivers) is configured in the observability stack, not here. ([ADR 0026](adr/operations/0026-observability.md))
- Gauges and circuit states live in one replica's memory; a collector disabled on purpose keeps alerting until its value is 9 days old. ([ADR 0026](adr/operations/0026-observability.md))
- Redis memory and evictions are not measured (no Redis exporter in the chart). ([ADR 0026](adr/operations/0026-observability.md))
- The off-site backup copy is off and no restore has been tested. ([ADR 0027](adr/operations/0027-runtime-topology.md))
- Staging (1 replica) has no failover; a prod deploy starts pods one after another (startup up to 300 s each). ([ADR 0027](adr/operations/0027-runtime-topology.md))
- Plain HTTP inside the cluster; sign-in for everyone changes that. ([ADR 0027](adr/operations/0027-runtime-topology.md))
- Never run `helm uninstall`: it deletes the Postgres and Redis StatefulSets. ([ADR 0027](adr/operations/0027-runtime-topology.md))
- The deployer's Kubernetes token does not expire; it sits in OpenBao and in the namespace. ([ADR 0028](adr/operations/0028-ci-cd.md))
- Terraform stays manual (`make bootstrap`). ([ADR 0028](adr/operations/0028-ci-cd.md))
- Deleting an old image needs the Harbor immutability rule disabled first, and enabled again after. ([ADR 0029](adr/operations/0029-release-versioning.md))
- A deleted row stays in the backups for up to 7 days; purges run daily, so a row can outlive its period by up to a day. ([ADR 0034](adr/operations/0034-data-retention.md))
- An observed resource seen again after its purge comes back as a new row with a new `first_discovered_at`. ([ADR 0034](adr/operations/0034-data-retention.md))
