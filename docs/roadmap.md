# Roadmap

Every open item lives here, so open work is tracked in one place. ADRs link to this page
from their "Roadmap" section. The code has no TODO or FIXME comments.

The baseline is v1.0.0, the first release (27 Oct 2026); the current release is v2.0.0, a major version because prod got an empty database for the single V1 schema.
Items are grouped by the order in which they matter. Inside a group, the first item is the most useful.

## 1. Launch hardening

### Product and legal
- Imprint and contact (EU). The Privacy and Terms pages exist; the Sources page lists attributions.
- Lawyer review of the Privacy and Terms pages and of the AI Act reading in
  [ADR 0038](adr/guide/0038-ai-content-marking.md): provider role, metadata-only marking, deadline.
- Lawyer review of the web-search quota ([ADR 0037](adr/search/0037-tavily-store-and-daily-quota.md)): whether an HMAC of
  the address or of the query is personal data, legitimate interest as the basis, and the wording on the transfer of
  queries to Tavily in the US.
- Lawyer review of back-office records ([ADR 0045](adr/edge/0045-back-office.md)): `admin_audit` keeps an
  administrator's GitHub id for 24 months, a review item keeps who decided it and their note, a skipped
  resource keeps the reason for the skip (`enrichment_queue_control`, until requeued), and `runtime_setting` keeps
  the GitHub id and reason of each change for 24 months, the newest per setting for as long as it applies. Is the line on the
  Privacy page enough for the people who run the site, and is 24 months right?
- Check each US source and GitHub for Data Privacy Framework certification, as the Privacy page states.
- Lawyer review of guide question embeddings ([ADR 0041](adr/guide/0041-guide-answer-cache.md)): whether an embedding of a
  question is personal data, the basis for keeping it up to a day, and the transfer of search and guide text to the
  NVIDIA API catalog in the US. The Privacy page lists the transfer, what NVIDIA receives and what its terms let it do
  with the text (6 Nov 2026); the lawyer confirms the wording and whether Art. 49(1)(b) GDPR can carry transfers made
  on every search.
- Silvio: buy the NVIDIA API subscription before the next prod release; the trial terms forbid production
  ([NVIDIA API Trial Terms of Service](https://assets.ngc.nvidia.com/products/api-catalog/legal/NVIDIA%20API%20Trial%20Terms%20of%20Service.pdf),
  v. 19 Sep 2025, §1.2 and §1.4). NVIDIA publishes no terms for a paid use of `integrate.api.nvidia.com`: §1.4 says the
  subscription's own terms govern production use. Before signing, get in writing that the subscription covers calls to
  the hosted endpoint, what NVIDIA does with the text (the trial terms let it improve its models, §3.3(iv)), how long it
  keeps it (§2.3, §2.4), where the endpoint runs, and whether a data processing agreement with standard contractual
  clauses is part of it. Then update the Embeddings sentence on the Privacy page.
- Get in writing from Nebius where `nvidia/Nemotron-3.5-Super-VL` and `nvidia/Nemotron-3_5-Lightning` are served,
  and that the Nebius DPA with its standard contractual clauses covers the US calls. The model catalog (read
  7 Nov 2026) lists the reasoning model in `us-north1` (US) and the fast model in `eu-north1` (Finland); the Privacy
  page says so. Nebius Inc. is DPF-certified EU-US only, not Swiss-US, so the Swiss transfer rests on those clauses: a
  lawyer checks the Privacy sentence on transfers before the release.
- Review each source's terms again for commercial use.
- Monetisation ([ADR 0057](adr/frontend/0057-monetisation.md)), before a setting is filled on prod:
  - Manual: create the GA4 property (EU data), retention 2 months; in enhanced measurement turn off "Page changes based
    on browser history events", site search and form interactions; Google signals off; a separate stream for staging.
    Check once in DebugView that no hit carries `q=` or `ask=`.
  - Manual: EthicalAds applied (6 Nov 2026), waiting for the publisher ID; set up GitHub Sponsors and a Stripe Payment
    Link whose confirmation page is `https://helper2.ai/support/thanks`; then fill `app.monetisation.*` per
    environment.
  - Check on staging that the browser console shows no CSP errors with each part on (the CSP lists `media.` and
    `server.ethicalads.io`; the client loads ads as JSONP from `server.`).
  - Lawyer: check the consent wording, the Swiss rules on ad labelling (UWG), whether EthicalAds (Read the Docs, Inc.,
    US) needs consent or a transfer basis (it receives the IP address and topic words from the page), and that the
    DSA ad duties do not apply.
  - Tax advisor: gifts and ad income as a private person in Switzerland, and whether a sole proprietorship must be
    registered. The Terms say a gift is not tax-deductible.
- AdSense ([ADR 0059](adr/frontend/0059-adsense.md)), before `app.monetisation.adsenseSlot` is set on prod:
  - Manual: Google approves the site; in AdSense "Privacy & messaging", the European regulations message with consent
    mode on for ads and for analytics; Auto ads and overlay formats (anchor, vignette, side rail) off; one display ad
    unit; then set its ID in `values-prod.yaml`.
  - Check on staging first, with the CSP report in the browser console: the AdSense CSP and every script of the app
    (the module preloads and lazy chunks run through the nonce and 'strict-dynamic'). After a yes in Europe,
    `googlefc.getGoogleConsentModeValues().analyticsStoragePurposeConsentStatus` must be 1: a 4 (not configured)
    keeps Google Analytics off.
  - Lawyer: our reading that GA4 needs TCF purposes 1 and 8 and vendor 755, plus consent mode's analytics storage;
    analytics without a question where `gdprApplies` is false (Swiss visitors included); legitimate interest for ads
    without personalisation; Google as an independent controller for AdSense; the consent tool's cookie names and
    lifetimes on the Privacy page; whether the consent tool, once loaded, seeing a later in-app search address is a
    transfer of search words to Google.
- Lawyer review of the awesome-list search index ([ADR 0055](adr/search/0055-search-inside-awesome-lists.md)): whether a
  tsvector of a list's words is a reproduction or a database extraction, keeping and showing section headings, and
  GitHub's terms for reading README files to build it.
- Lawyer review of deep search in awesome lists ([ADR 0058](adr/scout/0058-awesome-deep-search.md)): GitHub's terms
  for reading up to 40 README files a day and following links from list to list; keeping and showing links to
  third-party product sites with the link's words and the list's section (consent, liability for links under the DSA
  and Swiss law); a section heading used as a category as reuse of the list's selection or arrangement.
- Lawyer review of README and model-card quotes ([ADR 0043](adr/scout/0043-enrichment-gap-round.md)): the quotation
  right (Swiss URG Art. 25, EU InfoSoc Art. 5(3)(d)) for 20–300-character excerpts, also of repositories with no
  licence; GitHub and Hugging Face terms for reading and keeping them (the Hub `raw` path is not its API); author names
  in excerpts under the existing purpose with a public Art. 14 notice (Art. 14(5)(b)).
- Lawyer review of product-page reading ([ADR 0056](adr/scout/0056-evidence-ladder.md)): Tavily's written OK for
  Extract (received 6 Nov 2026); the quotation
  right for quotes from vendor pages; the transfer of page addresses to Tavily in the US; the Privacy wording on page
  cleaned page text kept up to 24 hours to retry, and on AI-estimated values; the Terms line on "AI-estimated" and "Self-declared by the vendor";
  whether an AI-estimated audience or tech level needs more than the Art. 50 label.
- SEO ([ADR 0050](adr/frontend/0050-pages-for-crawlers.md) is built; the resource page adds about 1 ms at p95 on
  staging, budget 20 ms). Next, in this order:
  - One Open Graph image for the brand (1200×630 in `public/`); one per resource only if links get shared often.
  - Hub pages per type (`/mcp-servers`, `/models`): when Search Console shows which types get impressions.
  - 410 for resources removed on purpose, once the catalog has a removed state; a sitemap index past 50,000 pages.
  - Not now: `llms.txt` (no major crawler uses it), IndexNow (the sitemap is enough at this size).
  - Manual: Google Search Console and Bing Webmaster Tools (domain property, DNS TXT); submit
    `https://helper2.ai/sitemap.xml` after the prod release; check the Cloudflare bot settings (today: AI training
    bots blocked, search allowed).

### Domain and identity
- DNS: `CAA` record for the edge's certificate authority; `v=spf1 -all` and DMARC `p=reject` if no mail is sent.
- Optional `H2_HF_TOKEN` on prod.
- `H2_SETUP_GITHUB_TOKEN` (read-only, public repositories) in `app_env` on staging and prod; without it "Set it up" reads
  GitHub anonymously, 60 calls an hour ([ADR 0031](adr/guide/0031-setup-guide.md)).
- Turn on the registered Tavily tier (more web searches per day for signed-in users) once that sign-in is live
  ([ADR 0037](adr/search/0037-tavily-store-and-daily-quota.md)).

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
  [docs/eval](eval/README.md) describes, then record the baseline. The set was developer-heavy: the 20 `everyday`
  queries (plain words, path `USE_AI`) now exist and wait for labels like the rest.
- Guide relevance judged by a second person, not only by the owner ([ADR 0030](adr/quality/0030-guide-evaluation.md)).

## 2. Operations

- **Service split, next steps** ([ADR 0069](adr/operations/0069-three-deployables.md)):
  - keep the previous release's assets in the web image: during a rollout a page from an old public pod can name an
    asset hash the new web pods no longer have, and Helm does not enforce the order web, public, back office;
  - a non-superuser owner ([ADR 0070](adr/operations/0070-app-roles.md)): `POSTGRES_USER=helper2` is a superuser, so
    the migrate Job's image could reach `COPY ... TO PROGRAM`. Keep a superuser only for the database's init, and let
    the owner own the database and its tables without superuser;
  - a narrower Redis split ([ADR 0070](adr/operations/0070-app-roles.md)): both apps share the rate-limit, budget,
    quota and source-cache keys, so the public app can reset a spend cap or poison the source cache. Give each app its
    own families where the code allows (the back office's caps under its own prefix), and drop `+eval` where a plain
    command does;
  - PR 5: CI and CD per component (path filters: `frontend/`, `apps/public`, `apps/backoffice`, a library change
    builds both apps), and image signing for every image;
  - Grafana: the dashboard and the alert rules filter on `application="helper2"`; the back office reports as
    `helper2-backoffice` (traces, metrics, logs) and needs its own panels and alerts (Scout, collectors, enrichment
    budgets, the embedding indexer);
  - the back office's Settings page shows its own groups and the guide's daily cap; the public app's other guide and
    setup settings are not there. Read them from the public app (an internal endpoint) or publish them to the
    database at startup;
  - the back office overview's budgets lack the setup guide's (`tool-guide`): declare it there as the guide's;
  - the release after ADR 0070's: drop the owner's and Redis' default password from `helper2-app-env` (Makefile
    `NOT_IN_APP_ENV` -> `CREDENTIALS`). They stay there for one release only, so a rollback to the old chart still
    starts Postgres and Redis;
  - an env Secret per app: the passwords have their own Secret ([ADR 0070](adr/operations/0070-app-roles.md)), but
    both workloads still read the rest of `helper2-app-env`, so the public pod holds the back office's keys (the
    Scout's sources) and the other way round;

- **Exporters.** Postgres and Redis exporters in the Helm chart, and an alert on Redis evicted keys
  (`allkeys-lru` can drop sessions, rate-limit windows and web-search counters under memory pressure).
- **Backups:**
  - test a restore;
  - add a periodic restore-check job;
  - turn on the off-site copy to R2 (`backup.offsite`) with a bucket-scoped token;
  - encrypt dumps on the client side before upload ([ADR 0027](adr/operations/0027-runtime-topology.md)).
- **Short-lived deployer token.** Mint it with the OpenBao Kubernetes secrets engine. The deployer token
  (the platform repo) does not expire ([ADR 0028](adr/operations/0028-ci-cd.md)).
- **Supply chain:**
  - set `postgres.image.digest` to the digest of `18.6-pgvector0.8.6-5` once CD has pushed that tag (with the
    Postgres exporter: one restart);
  - **18 Nov 2026:** end of the report-only period. On 6 Nov code scanning held 66 open Critical or High findings
    with a fix. The `-4` image (upstream rebuild of 6 Nov) cleared the Alpine ones; on 7 Nov all 35 left in the
    Postgres image (3 Critical, 32 High) were the Go standard library inside `gosu`, which the `-5` image no longer
    has. The app image holds 6 High (Debian `apt` and `libpcre2`), no Critical. Check the scan of `-5`, add any
    exception that is justified to `.grype.yaml`, then let the `scan` job fail on a Critical finding with a fix
    (`--fail-on critical` in `cd.yml`) and make it a need of `staging` there
    ([ADR 0046](adr/operations/0046-supply-chain.md)).
- **Release branches** ([ADR 0048](adr/operations/0048-release-branches-and-version-tags.md)):
  - before the change merges (every release needs it): let the OpenBao role `deploy-prod` read `ci/harbor`, check
    the Harbor robot may create tags; before the first push to a `release/*` branch: bind the role `deploy` to
    `refs/heads/release/*` too (incalza-me-infra), and add a branch ruleset on `release/*` like `main`;
  - a narrower Harbor robot for the prod deployer that may only add tags, not push images;
  - a release from a release branch skips staging. If hotfixes become frequent, give them a staging run first (a
    second staging namespace, or staging switched to the release branch for the time of the fix).

## 3. More than one replica

Prod runs 3 replicas (`app.replicas`, staging 1). The Scout and the embedding indexer run on one replica at a
time (ShedLock, table `shedlock` in Postgres); rate limits and sign-in sessions are in Redis, query translations,
query vectors and setup guide answers in the Postgres table `ai_cache`. Still per replica, on purpose:

- **Guide single-flight map.** Answers are in PostgreSQL and shared; two replicas may answer the same new question
  once each when it arrives on both at the same time.
- **Source cooldowns after a rate limit** (`SearchResources`), **circuit breakers and bulkheads** (`source-<id>`
  policies, [ADR 0033](adr/foundation/0033-resilience-policies.md)) and the Sources page's last outcomes (`SourceHealth`).
  Each replica finds a limited or failing source on its own, at the cost of a few more refused calls.
- **Guide concurrency limit** (`H2_GUIDE_MAX_CONCURRENT`, 8 per replica, 24 on prod). The daily model-call cap
  in Redis still bounds the cost.
- **Embedding indexer pause after a rate limit.** Only the replica that got the 429 pauses; the lock already
  keeps the cluster at one batch per period.

Open items:

- **Redis memory.** Sign-in sessions, rate-limit windows and web-search counters share the 128 MB `allkeys-lru`
  cache: under pressure an admin is signed out, or a client gets a fresh window or a fresh day early. Alert on
  Redis evictions (with the Redis exporter, section 2).

## 4. AI quality and cost

- LAWYER: does a model translation of the user's own query fall under the Art. 50(2) exemption for assistive or
  standard-editing functions? Until then it is labelled "Translated by AI" and marked (`interpretation.translatedQuery`,
  [ADR 0038](adr/guide/0038-ai-content-marking.md)).
- Watch whether the source `type`, now a source fact in enrichment, sends many proposals to CONFLICTING review
  (for example GitHub LIBRARY versus MCP_SERVER) ([ADR 0019](adr/scout/0019-evidence-gated-enrichment.md)).
- Cross-language retrieval in vector search.
- Gap round for resources enriched before it ([ADR 0043](adr/scout/0043-enrichment-gap-round.md)): they get one only when
  their fingerprint changes. Either pick PARTIAL and REJECTED resources once from the backlog under the gap cap, or wait
  for the next prompt version. Decide with `helper2.scout.gap{outcome="filled"}` after two weeks.
- Resources whose description is under 40 characters are never enriched, so they never get a gap round, even when they
  have a README. Lower the bar for them only if the gap round fills fields often.
- Evidence ladder follow-ups ([ADR 0056](adr/scout/0056-evidence-ladder.md)):
  - A fill-rate eval set: 50 accepted products with the fields a person read on their sites; measure verified,
    self-declared, estimated and wrong per field from `helper2.scout.fields`, before and after a prompt change.
  - PR E, only if the fill rate stays low: Tavily site search (`include_domains` = the product's domain) to find a
    pricing or download page the home page does not link.
  - Read a product's pages again after N days (now 30, by the ledger), so a price change shows; the ledger already
    keeps a hash of the text to tell a changed page.
  - The first code re-check (`RecheckEnrichments`) sends back to the model only resources that still miss facts:
    watch `helper2.scout.recheck` and the backlog for the first week.
  - The results preview has no enrichment data: it shows the card's facts only. Show the AI-estimated audience there
    too if people miss it.
  - The page cleaner reads HTML tags by hand, not with an HTML parser: a tag longer than 2000 characters stays as text,
    so a hidden element with such a tag keeps its text. Pages come from Tavily as text, so this is rare; move to a
    parser (jsoup or similar, compared first) if a page shows it.
- Similar-question reuse of guide answers ([ADR 0041](adr/guide/0041-guide-answer-cache.md)): prod runs it at 0.96;
  watch `helper2.guide.cache{result="similar"}`, and raise or lower the threshold only with a new sweep. Live, the
  planner's readings of two wordings differ more often than the evaluation set assumes.
- A Grafana panel for the share of kept picks ranked 9+ (`helper2.guide.picks{rank="9+"}`), next to the alert
  `Helper2GuidePicksFromLowRanks` ([ADR 0015](adr/guide/0015-ai-guide-evidence-gate.md)).
- Write the Scout's Tavily news and events to the same stored sets as searches, with their own share of credits
  ([ADR 0037](adr/search/0037-tavily-store-and-daily-quota.md)).
- **Signed provenance manifest for AI-written text** (C2PA-style): sign each guide answer with its model, time and
  AI-written fields, so the origin survives outside our page and API. Today the marking is metadata only and is
  lost on copy ([ADR 0038](adr/guide/0038-ai-content-marking.md)). Do it when a standard and tooling for text exist, or
  if a regulator finds metadata alone too weak.
- Guide `WHEN` for a release: when our sources have no creation date, take the date quoted in a Tavily news item,
  through the evidence gate. Measure after the `WHEN` event filter ([ADR 0032](adr/guide/0032-guide-intents.md)).
- Confirm the guide latency targets (first useful event p50 3 s, p95 8 s) with two weeks of
  `helper2.guide.first_useful` on prod, and the nightly pass threshold (10 of 20) with two weeks of runs
  ([ADR 0026](adr/operations/0026-observability.md), [ADR 0030](adr/quality/0030-guide-evaluation.md)).
- A question from a shared `?ask=` link is asked on page load. Asking only after a click would stop a link from
  spending model calls for its visitors (one cached answer per question limits it today).

## 5. Search and catalog

- Indexed resources left without source records are not deleted (only observed ones are).
- On a resource's first search, the discovery date is unknown, because catalog writes run off the request path
  ([ADR 0004](adr/foundation/0004-persistence-and-provenance.md)).
- "Works with" flags on index hits name `helper2-index` as their source, not the source that set the tag, and only
  the first 20 tags (alphabetical) are kept, which can drop a flag ([ADR 0013](adr/search/0013-works-with-flags.md)).
- The MCP Registry asks for hourly polling, but the Scout runs once a day (03:07 UTC) in the cluster, so the
  registry is read daily ([ADR 0018](adr/scout/0018-scout-runner.md)).
- More registries, if their terms allow it. Consumer AI-tool coverage is thin.
- Live event collectors for hackathons, meetups, talks and workshops, so the curated community-events list
  ([ADR 0022](adr/scout/0022-next-events.md)) does not need a research pass to stay current. Candidates: Devpost and
  MLH (hackathons), Luma and Meetup (meetups, talks, workshops), Eventbrite. Each needs a terms review for public
  redisplay first, must state the days and the format (online, in person) in a form code can read, and Meetup's
  API needs a paid plan. The live web events scout ([ADR 0067](adr/scout/0067-live-web-events-scout.md)) covers part of
  this from Tavily, once Tavily confirms.
- Categories ([ADR 0053](adr/search/0053-category-filter.md)): label 150 resources in
  `backend/apps/public/src/test/resources/eval/categories-labelled.csv` and run `./gradlew evalTest --tests
  ai.helper2.eval.CategoryEvalTest`; tune the words of the categories with low precision or recall; consider a model
  only for the categories where code stays below the bar. Then fold use case into category (one facet, not two).
  Show the health and legal notices on the resource page of a resource in those categories.
- An admin UI for the curated list.
- Writing about a resource ([ADR 0062](adr/scout/0062-writing-about-a-resource.md)), next steps:
  - curated fields ([ADR 0066](adr/edge/0066-curated-field.md)): set platforms and use cases (labels rebuilt from the
    records); a "back to the sources" action; let the editorial seeds keep an admin's value on the same entry;
  - on a model's or product's page, list the articles about it ("In the news").
- Discovery hosts ([ADR 0063](adr/scout/0063-discovery-hosts.md)): show on each host how many candidates it
  kept or dropped in the last 30 days, so a wrong entry shows; let the admin move a host to the other list in one step
  instead of remove and add; a public-suffix check instead of the short list of registry second levels.
- Search inside awesome lists ([ADR 0055](adr/search/0055-search-inside-awesome-lists.md)): once the search set has
  grades, compare recall and nDCG@10 with and without list words, and tune the half weight from that, not before.
  Then decide on embeddings of entry lines.
- Deep search in awesome lists ([ADR 0058](adr/scout/0058-awesome-deep-search.md)): follow a list known only by its
  topics (one facts call per candidate link, measured first); read `<a href>` links in READMEs; measure the category
  precision from headings once the category labels exist.
- Source requests: anyone can ask for a new source of information (a registry, an e-learning platform, a vendor's
  catalog, a newsletter) from the Sources page. A request names the site and what it lists; it goes to the admin
  review list, and an accepted request becomes a collector or a curated entry after a terms review for public
  redisplay. Rate-limited like issue reports; no sign-in needed. Courses and certifications (Coursera, O'Reilly,
  edX, Udemy, vendor programmes) are the first case ([ADR 0032](adr/guide/0032-guide-intents.md)).

## 6. Frontend

- More UI languages. The UI is English only; queries work in any language.
- TypeScript 7 and ESLint 10 (with `@eslint/js` 10) are held back in `.github/dependabot.yml`: on 27 Oct 2026
  `typescript-eslint` 8.72 needs TypeScript <6.1 and `eslint-plugin-jsx-a11y` 6.10 needs ESLint <=9. Remove the
  ignore rules once both plugins support the new majors.

- **Redesign, version 4 of the design system** ([0042](adr/frontend/0042-redesign-design-system-v4.md),
  [design/system](design/system/README.md), boards in [design/redesign](design/redesign)). The fonts, the v4 tokens,
  the header, the footer, Home, Results (list and timeline), the resource page, Sources, Saved, the back office and
  the text pages (Mission, Privacy, Terms, Account, sign-in, 404) are built. Open points:
  - **Saved, drawn but not built:** Undo for a few seconds after Remove, "4 of 500" on the account list, and "We moved
    3 saved items to your account" right after sign-in. Each needs a decision first: Undo changes how Remove works,
    the cap and the moved count need fields on the bookmarks API.
  - **Mission: section order.** The board puts how the guide answers between What we promise and How we mark AI
    text; the page shows it after the locale sections. Needs a way to place it among them.
  - **API fields:** `newThisWeek` on GET /sources, `limits` on GET /me and `total` on the recent and upcoming responses
    are in the API, on Home, in the Sources table and on the Account page. `skipReason` on every source status is
    used by the coverage line on Results. The Mission, Privacy and Terms pages take their numbers from GET
    /api/v1/policy. The account panel shows `limits` (guide questions, web searches for search and for the guide, the reset time).
  - **Text pages: what stays typed.** Logs and request traces (3 months), usage totals (90 days) and Google's
    cookie lifetimes are outside the app, so the Privacy page types them. The rate-limit windows are words ("the
    length of the limit").
  - **Terms with the registered tier on:** the guide question limit is stated per IP address only. When signed-in
    users get their own guide limit, it needs a field on GET /policy and a sentence on Terms.
  - **Cost guard data:** with a week of data, check the share of "no pick" and failed questions that are given back
    (`helper2.guide.quota.give_back`, by `reason`) and how often a client reaches the free-answer cap
    ([0017](adr/guide/0017-guide-cost-guard.md)).
  - An `unknown` count per facet and an updated-date facet on the search response (Filters panel).
  - Sign-in with Google (phase 1). Passkey, email link and password (phase 2) need their own ADR, a threat model and
    the Privacy page first.
  - **Account, from the boards:** "higher daily limits when signed in" only once the API says the signed-in limits
    (from `limits` on GET /me, never typed), and "Saved items 4 of 500" once GET /bookmarks returns the account cap.
  - **Sign-in page, phase 2:** passkey, email link and password buttons, shown only when a provider is configured, after
    the phase 2 ADR above.
  - **Radar pause for everyone (WCAG 2.2.2), open point.** The radar's beam turns without end and its sources come and
    go. Signed-in users can turn it off on the account page; guests have only reduced motion and the hold while the
    search box has focus, and no switch. Find a pause for them that needs no new browser storage key, or decide the
    storage key with the Privacy page.
- **Home examples** ([0049](adr/search/0049-home-examples.md)), next steps in this order:
  - A lawyer confirms the Art. 50 points of [0049](adr/search/0049-home-examples.md) for accepted AI drafts; until then
    they are labelled and marked like any AI-written text.
  - Check now on one example, and an Italian example once one passes the check.

## 7. Architecture

- The Java use cases of catalog read its `internal` stores directly (`ResourceStore`, `ResourceCards`), and nothing
  checks them for framework types. Put the stores behind ports, then add the packages to the `DOMAIN` rule of
  `ModularityTest`, as identity and admin do
  ([ADR 0002](adr/foundation/0002-modular-monolith-hexagonal.md)). Only the JDBC adapters are guarded today
  (`useCasesDoNotNameJdbcAdapters`).
- The model helpers live in the open `platform` module (`platform.llm`), so every module can use all of them. A
  closed `llm` module with a small public API would make each use explicit
  ([ADR 0014](adr/guide/0014-llm-integration.md)).

## 8. Documentation

- After the hackathon judging ends (15 Dec 2026), move `docs/hackathon` and the README's hackathon section out of
  the repo.

## 9. Known limits

What each decision leaves open. The ADRs describe only what the code does; their limits live here. Limits that
already have a task above are not repeated: SEO (section 1), backups, exporters and
tokens (section 2), per-replica state (section 3), the labeled query set (section 1) and module coupling (section 7).
Fix an item or accept it; when it no longer holds, remove it.

### Foundation

- Startup is slower than a native binary: Flyway and the language models load first; the startup probe allows up to 300 s. ([ADR 0001](adr/foundation/0001-stack-and-runtime.md), [ADR 0027](adr/operations/0027-runtime-topology.md))
- Two backend languages raise the entry cost for a new developer; Kotlin stays in the rule packages. ([ADR 0001](adr/foundation/0001-stack-and-runtime.md), [ADR 0002](adr/foundation/0002-modular-monolith-hexagonal.md))
- Generated API code is not committed: after a fresh checkout, run Gradle or npm once before the editor resolves the types. The guide SSE handler is hand-written, so the compiler checks only its event names. ([ADR 0003](adr/foundation/0003-contract-first-rest-api.md), [ADR 0015](adr/guide/0015-ai-guide-evidence-gate.md))
- A resource seen for the first time has no discovery date in that first response (the write is still queued); a dropped write delays it until the resource is seen again. On shutdown the queue gets 5 s; later writes are lost. ([ADR 0004](adr/foundation/0004-persistence-and-provenance.md))
- Bookmarks and reports are user data; the backup is the only copy. ([ADR 0004](adr/foundation/0004-persistence-and-provenance.md), [ADR 0027](adr/operations/0027-runtime-topology.md))
- During a Redis outage, budgets count in memory per replica (up to three times what was left of the day on prod); Redis is not ready while it loads the AOF; with `everysec` a crash loses at most the last second. ([ADR 0005](adr/foundation/0005-redis-disposable-cache.md))
- arXiv is not called while Redis is down. The total deadline of an HTTP call is checked on each read of the body, not while the request waits: the worst case is pool wait + connect + header wait + deadline + one read timeout. A retried call starts a new deadline and can add 1 + 2 + 4 s of backoff. ([ADR 0006](adr/foundation/0006-outbound-http.md), [ADR 0033](adr/foundation/0033-resilience-policies.md))
- The Kubernetes Secret is base64 in the cluster: namespace access means secret access. A renamed variable needs its OpenBao entry rewritten by hand (an old name is ignored); an `app_env` change needs `make credentials` and a deploy. ([ADR 0007](adr/foundation/0007-configuration-and-secrets.md))
- Resilience4j is one more library to keep current with Spring Boot. ([ADR 0033](adr/foundation/0033-resilience-policies.md))

### Search

- A search waits for the slowest answering source up to its deadline: Tavily (6 s) sets the worst case, and a non-English query can start most sources up to 2.5 s later for translation. ([ADR 0008](adr/search/0008-federated-search.md), [ADR 0011](adr/search/0011-language-aware-search.md))
- Facets and filters see only the hits fetched for this query (at most 30 per source), not the whole catalog. ([ADR 0008](adr/search/0008-federated-search.md), [ADR 0010](adr/search/0010-filters-facets-dates-timeline.md))
- A package found without its repository is a result of its own until a search finds both. ([ADR 0008](adr/search/0008-federated-search.md))
- The popularity formula exists twice, in SQL (`h2_resource_popularity`) and Kotlin (`Ranking.popularity`); change them together. Index stage 1 can cut partial matches when more than 300 rows match. ([ADR 0009](adr/search/0009-ranking.md))
- The timeline pages 50 results at a time, so a month can split across pages. A wrong early date from one source moves the result; the badge shows the dispute. ([ADR 0010](adr/search/0010-filters-facets-dates-timeline.md))
- The English/Italian lexicon still runs next to translation, so Italian gets two kinds of help and other languages rely on the model. ([ADR 0011](adr/search/0011-language-aware-search.md))
- A new or changed resource has no current vector until the indexer reaches it. The 0.65 cutoff comes from one set of measurements. A text the API always rejects is never skipped: it stays in the batch at its popularity rank, fails every batch it is in and takes one of the 5 single calls each tick. Decide whether such a row is set aside after a few failures. ([ADR 0012](adr/search/0012-vector-search-pgvector.md))
- “Works with” tag lists are kept by hand; no tag means no flag (not “does not work”); index hits name `helper2-index`; the 20-tag limit can drop a tag. ([ADR 0013](adr/search/0013-works-with-flags.md))
- Accepted risk (owner, 30 Oct 2026): rotating addresses can drain the monthly Tavily credits while no cap is set; searches then answer from stored sets and other sources. Failing closed loses fresh web results for a Redis outage. ([ADR 0037](adr/search/0037-tavily-store-and-daily-quota.md))
- Categories ([ADR 0053](adr/search/0053-category-filter.md)) are as good as their words: a tool that uses none of a category's words has no category. Facet counts see only the hits of this query. A changed category filter on a page where the guide was asked asks the guide again (one more AI answer, unless cached). The guide's comparison and explanation subject searches ignore the filter on purpose.
- Awesome-list words are English-stemmed: a list in another language matches only exact word forms. An entry whose repository was renamed since the list was written links the old URL and is left out of the index. ([ADR 0055](adr/search/0055-search-inside-awesome-lists.md))
- Deep search in awesome lists skips product sites on plain http, on an IDN (punycode) name or linked only through HTML. A list linked under a plain heading whose name does not say "awesome" is not followed. A category given by a list heading stays until the resource's categories are assigned again. All seed lists are read every pass, even when the first ones fill the entry cap. ([ADR 0058](adr/scout/0058-awesome-deep-search.md))
- The web-search quota is taken before the call, so a call the circuit refuses still counts. A stored hit is read back with two short queries; a set not yet stored is a miss. ([ADR 0037](adr/search/0037-tavily-store-and-daily-quota.md))

### Scout

- A scheduled run can start up to one `H2_SCOUT_TICK` (10 min) after its cron time. A renewal-thread pause longer than one lease lets another replica start a second run. ([ADR 0018](adr/scout/0018-scout-runner.md))
- Strict enrichment rules reject some correct values; the review queue grows with every partial run and needs a person. ([ADR 0019](adr/scout/0019-evidence-gated-enrichment.md))
- The gap round reads a README once per fingerprint: a README that changes while the source records do not is not read again, and one that fails to load (timeout, 5xx) is not tried again until they change. A gap slot is spent when the second model call fails. README text never supports a summary claim. ([ADR 0043](adr/scout/0043-enrichment-gap-round.md))
- Hugging Face provider models get no enrichment (the list API has no description). Tavily discovery finds consumer tools, hosting and prompt libraries, but each product waits for an admin ([ADR 0051](adr/scout/0051-tavily-discovery.md)); measure the weekly review load and the share accepted, then tune `min-score` and the categories. Course pages wait for an admin each week, and an accepted course is still added to the curated list by hand: index it on accept, like resource candidates. ([ADR 0020](adr/scout/0020-scout-collectors.md))
- Tavily discovery follow-ups ([ADR 0051](adr/scout/0051-tavily-discovery.md)): a weekly alert on the Scout's credits (`helper2_live_web_credits_total{feature=~"scout-discovery|scout-linkedin"}`); a host deny list and at most 2 list pages per host per pass; a replay evaluation on a recorded snapshot (the search facts are now kept per record and candidate) to tune each category's `min-score`, the list-title rule and the words; drop vendor compare and alternatives pages; recognise Chrome Web Store, Firefox add-on and GPT links; shorter excerpts for press pages and no email addresses in excerpts; expire open candidates; allow only collectors that propose candidates as their source. A rejected candidate can come back once its review item is purged (180 days after the decision).
- Discovery categories ([ADR 0051](adr/scout/0051-tavily-discovery.md)): an accepted candidate keeps only its first finder's search facts in full in `discovery` (the others are in `foundBy`). A category's first day is its place in the list, so moving a category that never ran moves its first day. The Scout checks only its own daily credit cap, not the live search's global one. The Terms describe the labels the UI shows today ("AI-written", "AI summary", "Extracted by AI"); add "AI-estimated" when a value is shown with it. The health and legal notices are on the Sources rows and the admin candidate cards, not yet on the resource page of an accepted tool.
- LinkedIn via Tavily ([ADR 0061](adr/scout/0061-linkedin-via-tavily.md)): an accepted company page links to LinkedIn, not to the product's own site, until enrichment or an admin adds it; an accepted LinkedIn event has no days and is not in Next events until it is added to the curated list by hand; measure in the first weeks how many candidates a pass sends and how many are accepted, and drop a query or a page kind that brings only noise; its 20 searches fall on one day, so on that day the Scout's daily cap of 40 leaves discovery about 20 credits (stagger it or split the queries over the week if a category is skipped for the cap).
- A catch-up run that ends just before the cron time is followed by the cron's run a few minutes later ([ADR 0018](adr/scout/0018-scout-runner.md)).
- Live web events scout ([ADR 0067](adr/scout/0067-live-web-events-scout.md)): off until Tavily confirms in writing that its OK covers scheduled searches whose pages we list; the gate proves the days are written on the page, not that the page is right; it reads only English month names and ISO dates; a page about several events gives none; measure in the first weeks how many pages a pass reads and how many events the gate keeps, and drop a query that brings only noise; read schema.org Event data before calling the model.
- An event whose source gives no day is not listed; event days can be a few hours off far from UTC; conference dates are as correct as ai-deadlines; curated events need a research pass to stay current. ([ADR 0022](adr/scout/0022-next-events.md))

### AI guide

- `H2_LLM_MAX_RETRIES` above 0 adds attempts that no budget counts. Thinking off makes the chooser return fewer picks. ([ADR 0014](adr/guide/0014-llm-integration.md), [ADR 0030](adr/quality/0030-guide-evaluation.md))
- The gate proves a quote, not that it is true: a description written to be chosen passes the gate and the reason check. Strict rules reject some good picks. A resource outside the 24 candidates cannot be picked. ([ADR 0015](adr/guide/0015-ai-guide-evidence-gate.md))
- The reason check is a model's judgment and can miss or reject a claim; it costs one more call per question (the 600-call daily cap covers at most 200 fresh questions). ([ADR 0016](adr/guide/0016-guide-reason-check.md))
- Clients behind one shared address share one guide quota; the quota is taken before the question runs, so a failed question still counts. ([ADR 0017](adr/guide/0017-guide-cost-guard.md))
- The setup guide is only as good as the tool's own docs; past the hourly fetch budget an uncached resource gets no README snippet; client config formats change and need a re-check. ([ADR 0031](adr/guide/0031-setup-guide.md))
- Asking back costs a round trip and a model call. A name is asked back only when our sources hold two kinds, and an unrelated project with a model's exact name also triggers it. A broad “what is new” question takes about 10 s longer; a subject slower than 10 s is reported as not found. ([ADR 0032](adr/guide/0032-guide-intents.md))
- `NEWEST` drops undated results silently. Events are found only in a source's own top hits, and the event words are a fixed list. Two more prompts (`compare`, `explain`) to maintain and evaluate. The course list needs editorial review. ([ADR 0032](adr/guide/0032-guide-intents.md))
- Similar-question reuse shares an answer only between questions read the same way; the reading cannot tell "this
  year" from "last year", and a question with no subject keeps no direction, which is why the threshold is high and
  most paraphrases still miss. ([ADR 0041](adr/guide/0041-guide-answer-cache.md))
- The research trail lists no duplicate, no pick dropped for lack of room and no prompt, so `rejected` can be larger
  than the list. A reused answer shows no queries and no search query. Lawyer: are AI-written
  search queries covered by Art. 50(2), and does the privacy text cover showing a cached answer built from someone
  else's question? ([ADR 0044](adr/guide/0044-research-trail.md))
- Lawyer: is metadata-only marking enough for text (it is lost on copy)? A new AI-written field must be added to the marking and its tests by hand. ([ADR 0038](adr/guide/0038-ai-content-marking.md))

### Edge and identity

- Roles live in the session: removing an admin takes effect at the next sign-in; a Redis outage signs the admin out. ([ADR 0023](adr/edge/0023-identity-and-admin.md))
- A GitHub OAuth App allows only one callback URL, and each environment needs two (public host and admin host): use a GitHub App, or one OAuth App per host. A manual step, outside the repo. ([ADR 0035](adr/edge/0035-admin-host.md))
- Neither app migrates: the migrate Job does, and each app waits up to 4 minutes for it at startup. A migration must keep the old pods of both apps working until they are replaced. A new role password takes effect when the Job runs, a moment before the pods restart: an old pod opening a new connection in between is refused. ([ADR 0070](adr/operations/0070-app-roles.md))
- Both apps upsert the same `source` rows at startup, from the same settings; an env Secret that differs between the two workloads would make them overwrite each other's `enabled` flag. ([ADR 0069](adr/operations/0069-three-deployables.md))
- The overview's enrichment backlog count reads every resource that may wait (0.65 s at 250 000 synthetic resources, cached a minute; 1.7 to 1.9 s with 37 500 REJECTED resources and the queue decisions). A queue page where no REJECTED resource is due for a retry walks every REJECTED resource (0.4 s at 250 000), and so does the out-of-retries view (1.1 to 1.6 s with 37 500 REJECTED); a stored "last run failed" flag would let the index skip them. A budget that is never built (no model configured) is not listed. ([ADR 0045](adr/edge/0045-back-office.md))
- The catalog's backlog query reads the Scout's `enrichment_queue_control` (a listed exception in the table-owner test). If it starts to hurt, the catalog can own the table behind a port, as for the re-run request of ADR 0056. ([ADR 0045](adr/edge/0045-back-office.md) §7)
- Privacy check (privacy-legal): the skip reason in the enrichment queue is free text, like the review note. The form says "No personal data" and the code drops control characters, but nothing stops a name. Confirm whether the Privacy page must list the skip reason, and whether it needs a retention rule (today it stays until a requeue, a new skip or the resource's delete). ([ADR 0045](adr/edge/0045-back-office.md) §7)
- Privacy check: the review decision note is free text kept with the review item. Write no personal data in it; confirm with privacy-legal whether the Privacy page must list it.
- While Redis is down each replica counts alone (up to 3× the limit on prod). Clients behind one shared address share one limit. ([ADR 0024](adr/edge/0024-http-edge-protection.md))

### Frontend

- With ads on, public pages allow inline styles (`style-src 'unsafe-inline'`) for the EthicalAds client. The ad slot's height is fixed by hand; check it against real ads on staging. ([ADR 0057](adr/frontend/0057-monetisation.md))
- Google Analytics hosts are listed by hand in the CSP; if Google changes them, hits are blocked until the list is updated. ([ADR 0057](adr/frontend/0057-monetisation.md))
- A user who never presses Ask the guide (or Shift+Enter) never sees the guide's picks: Enter only searches. ([ADR 0025](adr/frontend/0025-frontend-one-box.md))
- knip does not read locale files or CSS: a text key or a style that only dead code used stays until it is removed by hand (today the `freshness.*` texts and `.badge.freshness`). Moderate `npm audit` advisories in build and test tools are only reported. ([ADR 0054](adr/quality/0054-dead-code-and-dependency-audit.md))
- No browser end-to-end tests: frontend tests run in jsdom only, also in CI. ([ADR 0025](adr/frontend/0025-frontend-one-box.md), [ADR 0028](adr/operations/0028-ci-cd.md))
- A recent answer can be up to 5 minutes old (50 entries, 5 minutes, fixed). A new page that shows data the user changes must pass `cache: false`. ([ADR 0039](adr/frontend/0039-frontend-recent-answers-cache.md))
- The picks bar takes about 110 px also when the guide has nothing to say, and shows no step-by-step progress; there is no button to close the guide's answer. Two result layouts to build and test. ([ADR 0040](adr/frontend/0040-frontend-design.md))

### Operations

- Alert delivery is configured in the observability stack, not here. A collector disabled on purpose keeps alerting until its value is 9 days old. Histogram buckets add series per timer. ([ADR 0026](adr/operations/0026-observability.md))
- A guide stream or any request still running 30 s after SIGTERM is cut during a deploy (`app.shutdown.drainSeconds`); the guide's own deadline is 90 s. ([ADR 0027](adr/operations/0027-runtime-topology.md))
- Staging (1 replica) has no failover; a prod deploy starts pods one after another (up to 300 s each). Plain HTTP inside the cluster; sign-in for everyone changes that. Never run `helm uninstall`: it deletes the Postgres and Redis StatefulSets. ([ADR 0027](adr/operations/0027-runtime-topology.md))
- The scanner versions (Syft, Grype in `.github/actions/install-scanners`) are pinned by hand: Dependabot does not see them. Grype is built from source in each scan job (about 2 minutes); the weekly scan reads only the latest release, not an older one still on prod after a rollback. ([ADR 0046](adr/operations/0046-supply-chain.md))
- Deleting an old image needs the Harbor immutability rule disabled first, and enabled again after. ([ADR 0029](adr/operations/0029-release-versioning.md))
- A release that has no version tag in Harbor (one made before release tags) is deployed by its commit, not by its version. ([ADR 0048](adr/operations/0048-release-branches-and-version-tags.md))
- A deleted row stays in the backups for up to 7 days; purges run daily, so a row can outlive its period by a day; a purged observed resource seen again comes back with a new `first_discovered_at`. ([ADR 0034](adr/operations/0034-data-retention.md))
- Runtime settings ([ADR 0060](adr/operations/0060-runtime-settings.md)): a change reaches the other replicas within about 20 s, so for those seconds two replicas can cut off at two limits. The Settings page's read-only groups show the startup value for every other setting. A change has no Grafana annotation yet, so a dashboard does not show when a limit moved. A limit raised for one day stays until someone resets it: an expiry ("until midnight UTC") would need one more column. The six maximums are first guesses (2.5 to 5 times today's prod values); revisit them after a month of use. A new `H2_` default with an override in place changes nothing until the override is reset.

### Quality

- 50 guide questions is still a small sample, and the nightly run keeps 20 of them; the script measures the stream's last event, not the browser; an ungraded pick counts as not relevant; the candidates of a question with no answer come from its search terms sent again to `/api/v1/search`, not from the guide's own 24 (the answer carries only 3), so they are close, not exact; the hand-written notes describe the previous run until updated. The nightly run leaves out the intent cases; staging visitors share what it leaves of 150 guide calls a day; it needs the staging address reachable from GitHub's runners. ([ADR 0030](adr/quality/0030-guide-evaluation.md))
- Frozen search snapshots age; pooled judging leaves results outside every top 20 unjudged; labelling takes about 5 hours per judge; Tavily credits are spent once per recorded query. ([ADR 0036](adr/quality/0036-search-evaluation-harness.md))
