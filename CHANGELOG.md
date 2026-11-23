# Changelog

One entry per release. The ADRs say why the code is as it is ([docs/architecture/decisions](docs/architecture/decisions/README.md)); this page says what
each release holds for the people who use, run or build on helper2.ai. Versions are calendar versions, `YYYY.N.P`:
the year, the number of the release in that year's month series and the patch
([ADR 0045](docs/architecture/decisions/ADR-0045-releases-and-versions.md)). A breaking change goes on the first line of the
release notes.

## Unreleased

On `main`, not yet on a release branch.

### What users get

- **"awesome list" or "curated list" in a search** asks for awesome lists, in every language the search reads.

### What operators run

- **Release notes** list the pull requests of `main` a release ships and none of the release's own pull requests;
  `release` takes `notes_since` for a release below that holds no commit of `main`.

## 2026.12.0 (2026-11-23)

### What users get

- **The home lists** stand side by side as log rows on wide screens: the date and type in a fixed column, the title
  with its source in full under it. Phones keep the tabs.

### What operators run

- **Two migrations**, `V2026.11.11.1` and `V2026.11.11.2`, both additive: the audit log takes the removal of a
  discovered list that is gone, and an audit value or reason holds up to 1000 characters.
- **Lists with a long address** are added and removed in the back office, and the audit entry keeps a curated value and
  the address of a removed resource whole.
- **Releases:** a pull request into a release branch and a release's pull request back into `main` are merged with a
  merge commit, so a release branch carries `main`'s history.

## 2026.11.10 (2026-11-22)

A patch of 2026.11.9.

### What users get

- **Directory lists find the item's own site:** a link of a list is followed, at most 3 steps, to the page of the item
  itself, also when the same entry links an excluded site such as x.com.
- **A web page list that its site says is gone** no longer names its resources.

### What operators run

- **Two migrations**, `V2026.11.10.1` and `V2026.11.10.2`, both additive.
- **robots.txt and caps:** when robots.txt redirects to the www twin and that request meets a daily cap, a host cap or
  an open circuit, the link waits instead of being recorded as refused for a day.

## 2026.11.9 (2026-11-22)

### What users get

- **10 web searches a day** for search and 10 for the guide, for each visitor.
- **A rating from 1 to 5** on the resource page, from the votes the directories state, with the number of directories
  it comes from. It does not change the order of the results.
- **Fresher popularity:** the repository sweep reads the stars of a GitHub repository again.
- **Better facts:** the re-check tests every field that must state its value; a LinkedIn post is never the event.
- **The guide** reads the model's answer as a stream, so the first words come sooner.

### What operators run

- **Three migrations**, `V2026.11.9.1` to `V2026.11.9.3`, all additive.
- **The embedding model runs in the cluster:** one `helper2-embedder` for prod and staging, 4 threads and 4 CPU in
  prod, no hosted endpoint. Prod indexes one text every 3 s, staging one a minute.
- **Directory host lists:** the Scout reads the lists of a directory host only after an administrator turns the
  reading on for that host. A list page waits for review and is never indexed by itself; what a list named is kept
  until the list is read again, and a GitHub list that is gone no longer names its resources.
- **Runtime settings:** the Scout's daily search and page-read credits are set on the Settings page, 1,000 a day by
  default, at most 3,000. A runtime switch is shown, changed and audited as true or false.
- **Google ads** start and stop from the back office: one box below the search results and one below a guide answer.
  The switch is off in prod.
- **The guide judge runs in prod**, on its own daily cap.
- **Logs and alerts:** the live web credits alert fires at 2,500 a day; an unavailable source, a failed query
  embedding and a refused embedding batch each leave their reason in the log.
- **The public app gets only its own addresses**; the web image answers the rest.

## 2026.11.8 (2026-11-20)

### What users get

- **The site for crawlers and shares.** The pages carry a body a crawler can read, the sitemap is an index of parts,
  a hub page per resource type lists its newest resources, `/llms.txt` says what helper2.ai is, and a shared link
  shows an image. Changed pages go to IndexNow once a day.
- **The resource page shows every list** a resource was found in.
- **Search:** new words start without the filters of the words before; a word for the user's own device is not sent
  to the keyword sources.
- **The guide** rides out a short provider outage; each guide model has its own circuit.
- **The Privacy page** names the countries where the AI models run.

### What operators run

- **Five migrations**, `V2026.11.8.1` to `V2026.11.8.5`. `V2026.11.8.1` rewrites the prompt version of past
  enrichment runs to the prompt hash.
- **Lists the Scout reads** are added in the back office: GitHub lists and web pages. Every link of a list entry goes
  to the rule of its kind; an arXiv paper or an npm package a list links to is read from its source. A sweep visits
  every record with a GitHub repository.
- **Review research:** bulk Accept takes the candidates the research kept, with the values it confirmed, and refuses
  research a later edit made stale. A decided candidate still shows its research.
- **An admin removes a resource** that is not about AI, and the Scout does not add it again.
- **Bulk work runs on the fast model.** A text the embedding provider refused stays left out across restarts.
- **Each backup run writes its own dump**, so a run by hand keeps the nightly one.

## 2026.11.7 (2026-11-18)

### What operators run

- **The review research assistant runs on prod.**
- **The awesome-list re-check** survives a missing README, a rate limit and an open circuit.

## 2026.11.6 (2026-11-18)

`V2026.11.6.5` rewrites data: a course candidate's note moves into its details and the note is set to NULL. The
release before reads both forms, so the canary is safe.

### What users get

- **Skeleton loaders** on every page that loads data.
- **No shadow library is ever a result**, from any source.
- **An awesome list is its own type** and is never taken for the tool it lists.
- **The guide prompts** state only the rules the code enforces.
- **A request that ends after sign-in** replaced its session no longer fails.

### What operators run

- **Seven migrations**, `V2026.11.6.1` to `V2026.11.6.7`. The public app role gets no right on new tables or on the
  back office's locks.
- **Research on review candidates:** every step stored and streamed, a live run can be watched, queued runs wait for
  the budget, and tool results reach the models cleaned and escaped.
- **The back office keeps** the list of shadow libraries no search shows, and a known target can be a tool such as
  VS Code.
- **A judge on staging** scores the claims the guide's verify step checked.

## 2026.11.5 (2026-11-18)

### What users get

- **Events:** the Events page lists every upcoming event, with AI conferences from confs.tech; the Scout reads the
  page of an undated event, and a conference with side events is one event.
- **The guide** proposes five picks and shows the first three that pass every check; every quote links the page that
  holds it.
- **Search:** MIT stays a search term; one weak word like prompt or agent does not make a resource about AI; a
  package build for one system, an MCP server of a collection repository and a stranger's package are each handled
  as what they are.
- **The Privacy page** states how long past events are kept.

### What operators run

- **The back office:** the review list reads as titles, 50 rows a page; the Scout runs page shows the latest run of
  every source, failures first; the Overview groups the enrichment issues of the last 7 days; the catalog is checked
  against the AI word rule.
- **Alerts:** one on a single out-of-memory kill; the Scout stale alerts follow each collector's interval.
- **Redis's memory limit** is 3.5 times `maxmemory`, so an AOF rewrite cannot get it OOM-killed.
- **A release branch** can be cut from main's commit.

## 2026.11.4 (2026-11-16)

### What operators run

- **A text the embedding provider refuses** no longer blocks the indexer.

## 2026.11.3 (2026-11-16)

The first release of this repository. It is the starting point: the product as it is released.

### What users get

- **One page, one box.** A search covers tools, models, MCP servers, agents, prompts, libraries, papers and news.
  The state of the page is in the address, so Back, a reload and a shared link show the same thing.
- **A disclosed set of sources.** Every search says what happened to each source: answered, cached, timed out,
  rate-limited, paused. A result found by several sources is one row. Dates, popularity and facts that no source
  states are never filled in: the page says "Not stated".
- **Search in any language.** A query is translated for the sources; the catalog is searched by words and by
  meaning. Results can be filtered by category, type, use case, source, license, pricing and platform, sorted, and
  shown as a list or on a timeline. **Best for** ranks for people who use AI apps or for people who build with AI.
- **Ask the guide.** The guide runs only when asked. It picks at most three of the results it retrieved, each with a
  reason and a quote that code has checked word for word in the source. It compares two things, says what a thing
  is, asks back when a question has two readings, and says so when nothing fits. "How the guide decided" shows its
  steps. A daily number of AI answers per visitor; the page says how many are left.
- **Every AI-written text is labelled** on the page and marked machine-readable in the HTML and the API.
- **Resource pages** show the known facts first and the proof one click away: each source, each field with its
  origin, the evidence. Each has a readable address. **Set it up** gives the run options and the config snippet for
  an AI client, and a step-by-step guide checked against the tool's own documents.
- **Home, News and Events.** New in the catalog and Coming up on the home page; a News page and an Events page with
  their filters in the address. An event shows only the days a source states.
- **No account needed.** Signing in with GitHub keeps only an id and a name, and moves saved items to the account.
  The account page shows what is left today and deletes the account.
- **Report a problem** on any resource page: a person reviews it.
- **Mission, Privacy, Terms and Sources pages** read their numbers from the running server, so they match the limits
  and retention periods in force.
- **Support and ads**, each only while its setting is on: gifts through GitHub Sponsors or Stripe, Google Analytics
  after consent, at most one marked ad per search, AdSense only on resource pages after Google's consent tool.
- **Accessible.** Keyboard and screen-reader use on every page, a light and a dark theme, no text under 12px.

### What operators run

- **Three deployables** from one Helm chart: the public app, the back office and the web image, with PostgreSQL 18
  (full-text and pgvector) and Redis 8 as a disposable cache
  ([ADR 0043](docs/architecture/decisions/ADR-0043-runtime-topology-helm-chart-three-deployables.md)).
- **One schema migration**, `V1__schema.sql`, run by its own Job before the apps start. Each app has its own
  database role and Redis user; the passwords are in their own Secret, `helper2-credentials`
  ([ADR 0004](docs/architecture/decisions/ADR-0004-persistence-and-the-schema.md)).
- **Configuration** through `H2_` variables; secrets in OpenBao. Six spending limits change at runtime from the
  Settings page of the back office, audited and undoable
  ([ADR 0007](docs/architecture/decisions/ADR-0007-configuration-secrets-runtime-settings.md)).
- **The back office** on its own host, over the VPN: overview, review queue, Scout runs, enrichment queue, home
  examples, audit trail, settings ([ADR 0038](docs/architecture/decisions/ADR-0038-back-office-and-review.md)).
- **The Scout** runs once a day on prod and fills the catalog from its collectors. A claim a model proposes is
  published only with evidence; the rest goes to review ([ADR 0014](docs/architecture/decisions/ADR-0014-evidence-gated-enrichment.md)).
- **Delivery.** CI and CD per component; images signed and deployed by digest, an SBOM and a scan per image, and a
  scan that stops a deploy on a Critical finding that has a fix. Releases come from release branches through a
  canary, with an automatic rollback ([ADR 0044](docs/architecture/decisions/ADR-0044-ci-cd-and-supply-chain.md),
  [ADR 0045](docs/architecture/decisions/ADR-0045-releases-and-versions.md)).
- **Observability.** Logs, traces and meters, a Grafana dashboard per app and alert rules with their tests
  ([ADR 0009](docs/architecture/decisions/ADR-0009-observability.md)). Daily purges keep each store to its retention period
  ([ADR 0010](docs/architecture/decisions/ADR-0010-data-retention.md)).
- **Setup from zero:** [deploy/k8s/RUNBOOK-new-repository.md](deploy/k8s/RUNBOOK-new-repository.md).

### What builds on it

- **Public API** under `/api/v1`, contract first: [api/public.yaml](api/public.yaml), with `api/admin.yaml` for the
  back office and `api/common.yaml` for what both share. Errors are typed RFC 9457 problems
  ([ADR 0003](docs/architecture/decisions/ADR-0003-contract-first-rest.md)).
- **`GET /api/v1/policy`** states every limit and retention period the text pages show.

### Legal

The Privacy and Terms pages need a qualified lawyer before the release; the open points are tracked in
[F9.3](https://github.com/silvioincalza/helper2.ai/issues/102). I am not a lawyer.
