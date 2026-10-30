# Roadmap

Every open item lives here, so open work is tracked in one place. ADRs link to this page
from their "Roadmap" section. The code has no TODO or FIXME comments.

The baseline is v1.0.0, the first release (27 Oct 2026); the current release is v1.0.1; the next is v1.0.2.
Items are grouped by the order in which they matter. Inside a group, the first item is the most useful.

## 1. Before the hackathon deadline (30 Oct 2026, 18:00 Zurich)

- **Full vector index before recording the video.** Every resource has an embedding
  ([ADR 0012](adr/0012-vector-search-pgvector.md)). The Scout fills the index over a few days (300 enrichments
  per day).

## 2. Launch hardening

### Product and legal
- Imprint and contact (EU). The Privacy and Terms pages exist; the Sources page lists attributions.
- Lawyer review of the Privacy and Terms pages and of the AI Act reading in
  [ADR 0038](adr/0038-ai-content-marking.md): provider role, metadata-only marking, deadline.
- Check each US source and GitHub for Data Privacy Framework certification, as the Privacy page states.
- Make server and Traefik logs delete after 3 months, as the Privacy page states.
- Review each source's terms again for commercial use.
- Tavily: get a written OK for public display of results, or unset `H2_TAVILY_API_KEY`.
- SEO: `robots.txt`, `sitemap.xml`, metadata and JSON-LD for detail pages. Detail pages are not crawlable
  today, because the frontend is a single-page app ([ADR 0025](adr/0025-frontend-one-box.md)).

### Domain and identity
- DNS: `CAA` record for the edge's certificate authority; `v=spf1 -all` and DMARC `p=reject` if no mail is sent.
- Register a production GitHub OAuth App, add profile `oauth-github`, set `H2_GITHUB_OAUTH_CLIENT_ID`,
  `H2_GITHUB_OAUTH_CLIENT_SECRET` and `H2_ADMINS=github:<numeric id>` ([ADR 0023](adr/0023-identity-and-admin.md)).
- ADMIN is decided at sign-in, so a change to `H2_ADMINS` needs a new sign-in.
- Optional `H2_HF_TOKEN` on prod.
- Admin back office on `admin.helper2.ai`, reachable only through WireGuard via Traefik, with no app-level
  network filter. Not built yet: today `/admin` is served on the public hosts and protected only by sign-in
  ([ADR 0023](adr/0023-identity-and-admin.md)).

### Quality gates
- **Browser end-to-end tests (skipped for now, owner decision 27 Oct 2026).** No browser journey is automated,
  and no tool is installed (the unused Playwright setup was removed). Later, choose the tool, then on the `demo` profile:
  search with a partial result and the source status panel; ask the guide, follow the streamed steps, close the
  answer and keep the search; back and forward; the theme switch. Then run them in CI.
  See [user-journeys.md](user-journeys.md) (SC-1 to SC-3).
- Manual accessibility pass on real browsers: keyboard, screen reader, contrast, mobile.
  - After a route change, the focused heading and the live region can both be read out.
- Load test of search at the expected peak: confirm p95 and that the rate limits do not trip for normal use.
- Run `./gradlew liveTest` against production egress.
- Ranking measured on a labeled query set ([ADR 0009](adr/0009-ranking.md)).
- Guide relevance judged by a second person, not only by the owner ([ADR 0030](adr/0030-guide-evaluation.md)).

## 3. Operations

- **Alerting.** Write alert rules for:
  - source failure rate and partial-result rate;
  - cache errors;
  - review backlog;
  - 5xx responses;
  - guide budget below 20%, Scout enrichment budget, embedding backlog.

  Today the metrics are exposed, but no rule exists ([ADR 0026](adr/0026-observability.md)).
- **Exporters.** Postgres and Redis exporters in the Helm chart.
- **Backups:**
  - test a restore;
  - add a periodic restore-check job;
  - turn on the off-site copy to R2 (`backup.offsite`) with a bucket-scoped token;
  - encrypt dumps on the client side before upload ([ADR 0027](adr/0027-runtime-topology.md)).
- **Short-lived deployer token.** Mint it with the OpenBao Kubernetes secrets engine. Today the deployer token
  in `deploy/k8s/terraform/rbac.tf` does not expire ([ADR 0028](adr/0028-ci-cd.md)).
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
- Make the admin "run Scout now" call asynchronous: return 202 with a run id. Today it is synchronous.

## 4. More than one replica

Prod runs 3 replicas (`app.replicas`, staging 1). The Scout and the embedding indexer run on one replica at a
time (ShedLock, table `shedlock` in Postgres); rate limits and sign-in sessions are in Redis. Still per replica,
on purpose:

- **Guide answers: in-memory LRU and single-flight map.** Answers are also in Redis, so a replica only misses a
  question it has not seen; two replicas may answer the same new question once each.
- **Query-vector cache** (`SemanticSearchSource`). A miss costs one embedding call (about 0.3 s) on that replica.
- **Source-health cooldowns** (`SearchResources`) and the Sources page's last outcomes (`SourceHealth`). Each
  replica finds a source's rate limit on its own, at the cost of one more refused call.
- **Query-translation cache** (`SpringAiQueryTranslator`). A miss costs one model call, inside the daily
  translation cap, which is counted in Redis.
- **Guide concurrency limit** (`H2_GUIDE_MAX_CONCURRENT`, 8 per replica, 24 on prod). The daily model-call cap
  in Redis still bounds the cost.
- **arXiv minimum interval** (1 request per 3 s) is kept per replica, so the cluster can send up to 3 requests
  in that window. Share it in Redis if arXiv starts refusing.
- **Embedding indexer pause after a rate limit.** Only the replica that got the 429 pauses; the lock already
  keeps the cluster at one batch per period.

Open items:

- **preStop delay.** A pod gets SIGTERM while it is still being removed from the Service endpoints; a few
  seconds of `preStop` sleep would stop the last requests from reaching it during a rolling update.
- **Scout run lock after a crash.** `scout-run` is held for at most the derived worst case (prod: 1 h of
  collectors + 200 enrichments x 40 s x 1 attempt, about 3 h 13 min). A pod killed mid-run blocks the Scout,
  run now included, until then. ShedLock's `KeepAliveLockProvider` would allow a short lock that is extended while the
  run is alive.
- **Redis memory.** Sign-in sessions and rate-limit windows now share the 128 MB `allkeys-lru` cache: under
  pressure an admin is signed out, or a client gets a fresh window early. Alert on Redis evictions (with the
  Redis exporter, section 3).

## 5. AI quality and cost

- The guide reason check is a model judgment and can miss a claim. Only the quote is proven
  ([ADR 0016](adr/0016-guide-reason-check.md)).
- Watch whether the source `type`, now a source fact in enrichment, sends many proposals to CONFLICTING review
  (for example GitHub LIBRARY versus MCP_SERVER) ([ADR 0019](adr/0019-evidence-gated-enrichment.md)).
- Resilience4j for outbound calls (rate limit, retry with backoff).
- Cross-language retrieval in vector search.
- Alert when the share of kept picks ranked 9+ grows (`helper2.guide.picks{rank="9+"}`): text written to be picked
  passes the quote gate ([ADR 0015](adr/0015-ai-guide-evidence-gate.md)). The counter exists; the alert and the
  Grafana panel do not.
- **Signed provenance manifest for AI-written text** (C2PA-style): sign each guide answer with its model, time and
  AI-written fields, so the origin survives outside our page and API. Today the marking is metadata only and is
  lost on copy ([ADR 0038](adr/0038-ai-content-marking.md)). Do it when a standard and tooling for text exist, or
  if a regulator finds metadata alone too weak.
- Run `scripts/eval-guide.mjs --adversarial` in a scheduled job after each deploy; today it is run by hand.
- A question from a shared `?ask=` link is asked on page load. Asking only after a click would stop a link from
  spending model calls for its visitors (one cached answer per question limits it today).

## 6. Search and catalog

- `ListRecentResources` and `ListUpcomingEvents` make two queries per item, and the evidence loaders make one
  query per assertion. Batch them.
- Indexed resources left without source records are not deleted (only observed ones are).
- The Apache client's response timeout applies per read, not as a total deadline per call
  ([ADR 0006](adr/0006-outbound-http.md)).
- The embedding backlog gauge does not count rows whose text changed after they were embedded.
- Conference files that the ai-deadlines collector skips are logged, not counted in a metric.
- On a resource's first search, the discovery date is unknown, because catalog writes now run off the request path
  ([ADR 0004](adr/0004-persistence-and-provenance.md)).
- "Works with" flags on index hits name `helper2-index` as their source, not the source that set the tag, and only
  the first 20 tags (alphabetical) are kept, which can drop a flag ([ADR 0013](adr/0013-works-with-flags.md)).
- Guide recommendations carry no "works with" flags (they are not built with `CardMapper.card()`).
- The MCP Registry asks for hourly polling, but the Scout runs once a day in the cluster, so the registry is
  read daily ([ADR 0018](adr/0018-scout-runner.md)).
- More registries, if their terms allow it. Consumer AI-tool coverage is thin.
- Live event collectors for hackathons, meetups, talks and workshops, so the curated community-events list
  ([ADR 0022](adr/0022-next-events.md)) does not need a research pass to stay current. Candidates: Devpost and
  MLH (hackathons), Luma and Meetup (meetups, talks, workshops), Eventbrite. Each needs a terms review for public
  redisplay first, must state the days and the format (online, in person) in a form code can read, and Meetup's
  API needs a paid plan.
- An admin UI for the curated list.
- Source requests: anyone can ask for a new source of information (a registry, an e-learning platform, a vendor's
  catalog, a newsletter) from the Sources page. A request names the site and what it lists; it goes to the admin
  review list, and an accepted request becomes a collector or a curated entry after a terms review for public
  redisplay. Rate-limited like issue reports; no sign-in needed. Courses and certifications (Coursera, O'Reilly,
  edX, Udemy, vendor programmes) are the first case ([ADR 0032](adr/0032-guide-intents.md)).

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

## 8. Architecture

- One owner per table: the Scout writes enrichment tables that the catalog reads. Spring Modulith cannot
  see coupling through the schema ([ADR 0002](adr/0002-modular-monolith-hexagonal.md)).
- `platform` is an open module and holds the LLM layer. Consider a closed `llm` module with a named interface.
- Move the source and catalog adapters into `internal` packages.

## 9. Documentation

- `docs/diagrams/software-architecture.svg` is about 5,800 px wide: readable at full size, too small when scaled
  down. Split it into one diagram per module group.
- `docs/diagrams/flow-0025-frontend-one-box.svg` has two crossing dashed edges near `HomePage`.
