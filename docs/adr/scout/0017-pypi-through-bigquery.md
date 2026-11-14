# 0017 — PyPI through BigQuery

**Depends on:** [0006](../foundation/0006-outbound-calls-and-resilience.md), [0011](0011-scout-runner.md), [0012](0012-scout-collectors-and-events.md), [0015](0015-reference-projects.md)

## Context
Python is the main language of AI libraries, and PyPI has no search API: its JSON API reads one package whose name
is already known. PyPI publishes the metadata of every release as a public Google BigQuery dataset,
`bigquery-public-data.pypi.distribution_metadata`. BigQuery charges by the bytes a query reads, and the first 1 TiB a
month is free. The table is partitioned by upload time: a dry run in project `helper2ai` (9 Nov 2026) read 150 MB for
one week of AI packages and 11.59 GB for the whole table.

## Decision
1. **One Scout collector, `pypi`, once a day.** `PypiCollector` asks BigQuery for the packages with the classifier
   `Topic :: Scientific/Engineering :: Artificial Intelligence`, one row per package (its newest upload), ordered by
   upload time. It reads the name, version, summary, home page, project links, keywords, licence expression and upload
   time. It never reads the author, maintainer or e-mail columns.
2. **First fill, then new uploads.** The first pass reads from the start of the table. A later pass reads from two
   days before the last pass, so a late row in the dataset is not missed (an upsert of the same package is harmless).
   A pass stops at the run budget (at most 5,000 packages). The cursor (`nextCursor`) is the upload time and the name
   of the last package read, and the next run reads strictly after that pair (`upload_time > @since OR name >
   @after`, in the order of the query), so packages with one upload time are neither read twice nor skipped. When the
   review list's daily cap held candidates back at the end of a pass, the runner stores `held-back`: the collector
   then reads the pass window again (two days before the last pass, or the last 30 days on a first fill).
3. **Index or review.** The rule is the index floor of `pypi` in the admission rule of the Scout
   ([0020](0020-resource-vitality.md) §11); the collector asks it for each package and builds a hit or a
   candidate. A package with a summary and a GitHub repository (in its project links or home page,
   `CanonicalUrl` cuts it to `github.com/<owner>/<repo>`) is indexed as a `LIBRARY` hit for Python: the PyPI page
   (`https://pypi.org/project/<name>/`, the normalised name), the repository, the summary, up to 12 keywords as tags,
   the licence and the upload time as last updated. The package and its repository are one resource
   ([0021](0021-resource-identity-one-resource-per-repository.md) §3). `Classification.typeOf` may make it an agent framework or
   another type, as for npm. A package without a summary or without a GitHub repository goes to the review list as a
   candidate, at most `candidates-per-day` (20) new ones a day, and only if its newest upload is from the last 30
   days. An older one is left out and counted (`old-without-repo`), so the first fill does not flood the review list.
4. **Our own Google sign-in.** `GoogleServiceAccount` reads the service account key (`H2_PYPI_BIGQUERY_KEY`, base64 of
   the JSON key file) and refuses a key whose `token_uri` is not `https://oauth2.googleapis.com/token`, so a key file
   cannot send the sign-in anywhere else. `GoogleAccessTokens` signs a one-hour JWT (RS256, `kid` = the key id, scope
   `https://www.googleapis.com/auth/bigquery`) with the JDK and swaps it for an access token (OAuth 2.0 JWT bearer
   grant, RFC 7523). The token is kept until five minutes before it ends. No Google library.
5. **BigQuery's REST API with our HTTP client.** `BigQueryClient` posts the query with named parameters to
   `jobs.query`, waits for an unfinished job with `getQueryResults` (at most 6 waits of 20 s), follows page tokens up
   to the row cap and reads timestamps as microseconds. Every query carries `maximumBytesBilled`
   (`H2_SCOUT_PYPI_MAX_BYTES_BILLED`, 20 GB): BigQuery refuses a query that would read more, before it runs.
6. **Off by default; startup checks the key.** `H2_SOURCE_PYPI_ENABLED` defaults to `false`. When it is on, the back
   office does not start without `H2_PYPI_BIGQUERY_PROJECT` and a readable key; the error names the variable, never
   its value. The public app only describes the source and never holds the key.
7. **Resilience.** The policy `collector-pypi` (circuit breaker and retry of the `collector` base config,
   [0006](../foundation/0006-outbound-calls-and-resilience.md)); the HTTP timeout is 60 s, above BigQuery's 20 s wait.

### Code
- `backend/apps/backoffice/src/main/java/ai/helper2/sources/pypi/PypiCollector.java`, `PypiCollectors.java`,
  `BigQueryClient.java`, `GoogleAccessTokens.java`, `GoogleServiceAccount.java`
- `backend/apps/backoffice/src/main/java/ai/helper2/sources/config/scout/CollectorsConfiguration.java`
- `backend/apps/public/src/main/java/ai/helper2/sources/config/scout/ScoutSourceDescriptors.java`
- `backend/libs/sources/src/main/java/ai/helper2/sources/config/CollectorDescriptors.java` (`pypi`),
  `CollectorProperties.java` (`Pypi`)
- `backend/libs/platform/src/main/resources/helper2-shared.yaml`, `backend/apps/backoffice/src/main/resources/application.yaml`
- Tests: `PypiCollectorTest`, `PypiCollectorsTest`, `BigQueryClientTest`, `GoogleAccessTokensTest`,
  `GoogleServiceAccountTest`, `ResiliencePoliciesConfigIT`, `*PropertiesBindingTest`

## Alternatives considered
- **google-auth-library 1.35.0** for the sign-in: maintained by Google, but it brings Google's HTTP client, Gson and
  Guava for one token call.
- **Google's BigQuery Java client:** more features, but gRPC and protobuf for one query.
- **PyPI's JSON API or Simple index:** no search, no topic filter; the index lists names only.
- **libraries.io:** a search API, but a key, rate limits and its own terms.
- **Workload Identity Federation** (no long-lived key): needs an identity provider Google trusts ([S2.7.2](https://github.com/silvioincalza/helper2.ai/issues/207)).

## Consequences
- Python libraries with the AI topic join the catalog, most of them merged with their GitHub project.
- Cost: the first fill reads about 11.6 GB, a daily pass a few hundred MB at most, far below the free 1 TiB. With
  the project in BigQuery sandbox mode (no billing account), nothing can be charged; a query over the free amount
  fails.
- Packages without the AI classifier are not found: many AI libraries do not set it. Measured on the staging catalog
  before adding more classifiers or keyword rules.
- PyPI rows are never deleted, so a removed package is not withdrawn from the catalog by this source.
- A candidate held back by the daily review cap on a page that is not the last of its pass is offered again only when
  the package has a new upload.
- A long-lived service account key exists. It is the only way without a Google identity federation ([S2.7.2](https://github.com/silvioincalza/helper2.ai/issues/207)).

### STRIDE
- **Spoofing:** HTTPS to `oauth2.googleapis.com` and `bigquery.googleapis.com` only; the key file cannot change the
  token address.
- **Tampering:** a package summary is data, never instructions; it goes through the evidence-gated enrichment like
  every record ([0014](0014-evidence-gated-enrichment.md)).
- **Repudiation:** each call has a span and the `http.client.requests` meter; each run has its record.
- **Information disclosure:** the key never appears in an exception, a log or `toString`; the service account has
  the BigQuery Job User role only.
- **Denial of service:** `maximumBytesBilled`, the 5,000-package cap per pass, the circuit breaker, the 20 review
  candidates a day.
- **Elevation of privilege:** the account can run queries and read public datasets, nothing else in the project.

### AI architecture check
- Covered: Tool & API (one read-only adapter), Knowledge & RAG (Python libraries in the index), Data (no schema
  change), Guardrails (no personal columns, cost cap), Observability (spans, run records), Security & Identity (own
  service account, least role, key in OpenBao), Human-in-the-Loop (review list for packages without a repo or summary),
  Cost & Reliability (free tier, byte cap, daily interval), Deployment (one switch, off by default).
- Not touched: Model, Agent Framework, Orchestration (the Scout runner as it is), Memory, Browser & Computer Use,
  Evaluation (gap: not measured on the staging catalog).

### Privacy and terms
- No visitor data leaves: the Scout sends a fixed query and a timestamp from the cluster.
- The query never reads authors, maintainers or e-mail addresses (`PypiCollectorTest` checks the SQL).
- The Privacy page says that we read PyPI's public package metadata through Google BigQuery (Google, United States),
  and which fields.
- The key lives in OpenBao and the environment, never in the repo or the logs.

#### Points for a lawyer
1. PyPI's terms for reusing package metadata (name, summary, links, keywords) from the public BigQuery dataset in a
   catalog, with a link to the PyPI page.
2. Google Cloud's terms for the sandbox project: use for a public service, and the data location (BigQuery public
   datasets are in the United States; only public package data and our query travel).

## Diagrams
[flow](../../diagrams/flow-0017-pypi-through-bigquery.svg) ·
[sequence](../../diagrams/sequence-0017-pypi-through-bigquery.svg)

## Backlog
- Decides: [F2.7](https://github.com/silvioincalza/helper2.ai/issues/37).
- Open issues that change it:
  - [S2.7.2](https://github.com/silvioincalza/helper2.ai/issues/207): BigQuery reached without a long-lived key.
