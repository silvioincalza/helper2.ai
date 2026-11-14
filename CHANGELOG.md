# Changelog

One entry per release. The ADRs say why the code is as it is ([docs/adr](docs/adr/README.md)); this page says what
each release holds for the people who use, run or build on helper2.ai. Versions are calendar versions, `YYYY.N.P`:
the year, the number of the release in that year's month series and the patch
([ADR 0045](docs/adr/operations/0045-releases-and-versions.md)). A breaking change goes on the first line of the
release notes.

## 2026.11.3 (unreleased)

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
  ([ADR 0043](docs/adr/operations/0043-runtime-topology-helm-chart-three-deployables.md)).
- **One schema migration**, `V1__schema.sql`, run by its own Job before the apps start. Each app has its own
  database role and Redis user; the passwords are in their own Secret, `helper2-credentials`
  ([ADR 0004](docs/adr/foundation/0004-persistence-and-the-schema.md)).
- **Configuration** through `H2_` variables; secrets in OpenBao. Six spending limits change at runtime from the
  Settings page of the back office, audited and undoable
  ([ADR 0007](docs/adr/foundation/0007-configuration-secrets-runtime-settings.md)).
- **The back office** on its own host, over the VPN: overview, review queue, Scout runs, enrichment queue, home
  examples, audit trail, settings ([ADR 0038](docs/adr/backoffice/0038-back-office-and-review.md)).
- **The Scout** runs once a day on prod and fills the catalog from its collectors. A claim a model proposes is
  published only with evidence; the rest goes to review ([ADR 0014](docs/adr/scout/0014-evidence-gated-enrichment.md)).
- **Delivery.** CI and CD per component; images signed and deployed by digest, an SBOM and a scan per image, and a
  scan that stops a deploy on a Critical finding that has a fix. Releases come from release branches through a
  canary, with an automatic rollback ([ADR 0044](docs/adr/operations/0044-ci-cd-and-supply-chain.md),
  [ADR 0045](docs/adr/operations/0045-releases-and-versions.md)).
- **Observability.** Logs, traces and meters, a Grafana dashboard per app and alert rules with their tests
  ([ADR 0009](docs/adr/foundation/0009-observability.md)). Daily purges keep each store to its retention period
  ([ADR 0010](docs/adr/foundation/0010-data-retention.md)).
- **Setup from zero:** [docs/recreate.md](docs/recreate.md).

### What builds on it

- **Public API** under `/api/v1`, contract first: [api/public.yaml](api/public.yaml), with `api/admin.yaml` for the
  back office and `api/common.yaml` for what both share. Errors are typed RFC 9457 problems
  ([ADR 0003](docs/adr/foundation/0003-contract-first-rest.md)).
- **`GET /api/v1/policy`** states every limit and retention period the text pages show.

### Legal

The Privacy and Terms pages need a qualified lawyer before the release; the open points are tracked in
[F9.3](https://github.com/silvioincalza/helper2.ai/issues/102). I am not a lawyer.
