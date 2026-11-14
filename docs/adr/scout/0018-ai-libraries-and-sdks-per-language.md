# 0018 — AI libraries and SDKs per language

**Depends on:** [0004](../foundation/0004-persistence-and-the-schema.md), [0006](../foundation/0006-outbound-calls-and-resilience.md), [0011](0011-scout-runner.md), [0013](0013-discovery.md), [0015](0015-reference-projects.md), [0023](../search/0023-catalog-full-text-index.md), [0032](../guide/0032-guide-cost-guard-and-answer-cache.md), [0038](../backoffice/0038-back-office-and-review.md), [0046](../operations/0046-evaluation-guide-and-search.md)

## Context
A developer asks "java sdk for claude", "rust llm library" or "go openai client". The answer is a library or an
official SDK, in one language, with a package to install. The catalog finds few of them:
- The topic leaders ([0015](0015-reference-projects.md)) search 12 topics at 3,000 stars or more, with no language. Official SDKs have few
  stars (anthropic-sdk-java 391, anthropic-sdk-ruby 379) and often no AI topic.
- A repo says nothing about the package to install. `com.anthropic:anthropic-java` is not in the repo's name.
- There is no live source per package registry: a live search per registry per query is slow and has no
  ranking signal.

### Measurement
Done on 9 Nov 2026 before any code, on 38 known AI libraries and SDKs in 7 languages (official SDKs of OpenAI,
Anthropic, Google, Mistral, Cohere, Ollama, AWS, Azure; LangChain4j, Spring AI, Semantic Kernel, LlamaIndex,
LangChain.js, Vercel AI SDK, langchaingo, Jlama, Tribuo, DJL, ONNX Runtime, Candle, Rig, async-openai, go-openai,
ruby-openai, Transformers). "Found" means a GitHub search returns the repo and it passes the AI word list
(`Classification.isAiRelated`). "Mapped" means deps.dev also names the repo's main package.

| Option | Found | Found and mapped |
|---|---|---|
| Topic leaders today (12 topics, ≥ 3,000 stars, no language) | 3/38 (8 %) | — |
| A: same 12 topics × 9 languages, floor 1,000 (Python, JS, TS) / 100 (others) | 7/38 (18 %) | 4/38 (11 %) |
| A': A plus 8 provider topics (openai, anthropic, gemini, ollama, openai-api, sdk, claude, llm-inference) | 15/38 (39 %) | 11/38 (29 %) |
| B: provider orgs only (openai, anthropics, google-gemini, googleapis, aws, Azure, mistralai, cohere-ai, huggingface, ollama, langchain-ai) | 21/38 (55 %) | 18/38 (47 %) |
| **A' + B** | **30/38 (79 %)** | **25/38 (66 %)**; **28/38 (74 %)** with the name guess (decision 4) |
| A' + B + 7 more orgs (microsoft, awslabs, run-llama, langchain4j, spring-projects, deepjavalibrary, oracle) | 34/38 (89 %) | 30/38 (79 %) with the name guess |

The last row is tuned on the test list itself (those orgs were picked knowing the answers), so the honest number is
the A' + B row. The shipped org list adds four of them (langchain4j, spring-projects, run-llama, microsoft) because
they are real AI framework and provider orgs.

deps.dev, on the same 38 repos:
- `GetProjectPackageVersions` names the main package for 29/38 (76 %). A reverse check (package → its latest
  version → its source repo) confirms 36/38 when the package name is known.
- The answer holds at most 1,500 versions, attestation mappings first. A Go pseudo-module (`GO_ORIGIN`, every tagged
  repo has one) can fill the 1,500 and push the real package out: openai-python and transformers come back with Go
  versions only, vercel/ai with 1,500 versions of `@ai-sdk/*` and not `ai`. Monorepos (aws-sdk-java-v2,
  azure-sdk-for-java) hit the cap with unrelated or test packages.
- Provenance: most mappings are `UNVERIFIED_METADATA` (the package says where its source is). `SLSA_ATTESTATION`
  for npm packages built with provenance (openai, @anthropic-ai/sdk, ai, @langchain/*), `PYPI_PUBLISH_ATTESTATION`
  for mistralai and ollama, `GO_ORIGIN` for Go. No `RUBYGEMS_PUBLISH_ATTESTATION` in the set.
- Look-alikes: `@depup/openai`, `@dqbd/openai` (npm forks), `group.springframework.ai:*` (a re-publish of Spring AI),
  `com.sonatype.central.testing.amazon:*` (aws), bundled copies named `a>1.0>@google/genai`.
- Volume: with floors 5,000 (Python, JS, TS) and 1,000 (others), the plan finds about 1,400 AI repos a pass (1,100
  from topics, 330 from orgs). Lower floors add volume, not known SDKs: the found share stays 30/38 from floor 100
  to floor 1,000, because the small official SDKs come in through the orgs.
- The org searches return the 100 most-starred repos of each org. The org floor of 300 stars keeps every official
  SDK of the list (the smallest is anthropic-sdk-csharp, 345 stars).

Missed even by A' + B: aws-sdk-java-v2 and azure-sdk-for-java (no AI word; the Bedrock and Azure OpenAI modules
live inside them), cohere-python (no AI word in name, description or topics), djl, tribuo, onnxruntime, spring-ai,
langchaingo (no matching topic, org not in list B). The shipped org list adds spring-projects and microsoft, which
reach spring-ai and onnxruntime. deepjavalibrary, oracle and awslabs were added after this measurement (they reach
djl and tribuo); the numbers above do not count them.

Measured with decisions 3 and 4: deps.dev and the name guess give the main
package for 32/38 (84 %). Found by A' + B and mapped stays 28/38 (74 %). Still not mapped: java-genai (no
link at deps.dev), semantic-kernel (only its Java packages inside the cap), spring-ai (real packages, but not
`spring-ai-model` in the first 3), onnxruntime, aws-sdk-java-v2 and azure-sdk-for-java (monorepos).

## Decision
1. **One new review collector, `github-sdks`.** It does not change the topic leaders. A pass is a fixed plan of
   GitHub searches:
   - every topic × every language: `topic:{t} language:"{l}" stars:>={floor} fork:false archived:false`, most stars
     first, 100 a page. 16 topics (the topic leaders' list without speech-recognition and structured-output, plus
     openai, anthropic, gemini, ollama, openai-api, sdk) and 9 languages with their own floor:
     `Java:1000, Kotlin:1000, Python:5000, TypeScript:5000, JavaScript:5000, Go:1000, Rust:1000, C#:1000, Ruby:1000`
     (`H2_SCOUT_SDKS_TOPICS`, `H2_SCOUT_SDKS_LANGUAGES`);
   - then every provider org: `org:{o} stars:>=300 fork:false archived:false` (`H2_SCOUT_SDKS_ORGS`, 18 orgs).
   - 162 queries a pass, 20 a run (`H2_SCOUT_SDKS_QUERIES_PER_RUN`), so 9 runs; the cursor is the index of the next
     query. A pass every 7 days. It runs only with `H2_GITHUB_TOKEN`.
   - **Pages.** A query reads up to 3 pages of 100 (`H2_SCOUT_SDKS_PAGES_PER_QUERY`), and stops at a short page or
     at `total_count` (GitHub's cap is 1,000). Results left unread count in
     `helper2.scout.sdks.unread{reason=beyond-pages}`; an answer with `incomplete_results` in `reason=incomplete`.
     Live, `topic:llm language:Python stars:>=5000` has 228 results and `org:microsoft stars:>=300` 723: the first
     is read whole, the second to its 300 most-starred repos.
   - **Rate limit.** The token is shared with the topic leaders, the awesome lists and the live GitHub source.
     Searches are 2 s apart (`H2_SCOUT_SDKS_SEARCH_PAUSE`). When `X-RateLimit-Remaining` falls to 10
     (`H2_SCOUT_SDKS_RATE_LIMIT_RESERVE`), the run stops after that query and returns the next query's index as the
     cursor: a partial batch, not a failure.
   - **Held back.** When the daily cap held items back at the end of a pass, the runner gives the cursor
     `held-back`; the collector starts the plan again from the first query.
2. **Only AI repos.** A repo passes the same fixed word list as every other collector. A repo whose owner is in the
   org list is marked `official`.
3. **Known and over-cap repos are dropped before deps.dev.** A repo already in the catalog or proposed before is not
   looked up. In the catalog means: the repo is a resource's address, a source record's address, or the repository of a
   source record (`ReviewQueue.repositoryListed`). The last one covers a Python repo indexed through PyPI
   ([0017](0017-pypi-through-bigquery.md)), whose resource lives at the pypi.org page and names the repo as its
   repository: it takes no review slot (`SdkReviewRoomIT`). Of the rest, official repos come first, and only as many as
   the day's cap leaves (`H2_SCOUT_SDKS_PER_DAY`, 500, minus the items `github-sdks` opened today) are looked up and
   proposed; the others count in `reason=over-daily-cap` and come back next pass.
4. **deps.dev names the packages.** For each repo (kept in memory for the run interval, at most 5,000 repos):
   - `GET /v3/projects/github.com%2F{owner}%2F{repo}:packageversions`. A 404 (a plain-text "project not found") is
     an empty answer, not a failure, and does not count on the circuit;
   - drop the Go module of a repo that is not in Go, bundled copies (`>` in the name), boms and parents (`bom`,
     `-bom`, `parent`, `-parent`), examples and samples;
   - look-alike guard for `UNVERIFIED_METADATA`: an npm scope, a Maven group or a NuGet prefix must be near the
     repo's owner or name (`@anthropic-ai` and `anthropics`, `org.tribuo` and `tribuo`; `@depup/openai` fails);
     a plain name must be a repo-name guess, start with one (`candle-core`), or be the owner's prefix and a guess
     (`google-genai` for `googleapis/python-genai`);
   - order: the repo's own registry first, then the most releases, then the shortest name; keep 3
     (`H2_SCOUT_SDKS_PACKAGES_PER_REPO`);
   - for each, the default version (`GetPackage`, `GetVersion`): version, publish date, licences, source repo. A
     package counts only when its latest version points back to the same repo. A failed lookup drops only that
     package; only a failed project call drops the repo's packages (it is still proposed, with none).
5. **The name guess.** In the repo's own registry (PyPI, npm, Cargo, NuGet, RubyGems; not Maven, whose names need
   a group, and not Go): when the answer holds 1,500 versions (the cap), or no kept package is named like the repo,
   the repo name is tried, then without its language or `-sdk` words (`anthropic-sdk-python` → `anthropic-sdk` →
   `anthropic`). A guess counts only when it points back to the repo, and it comes first. This finds `openai`,
   `transformers` and `ai` (vercel/ai, whose 1,500 versions are all `@ai-sdk/*`).
6. **One resource per repo, packages inside it** ([0021](0021-resource-identity-one-resource-per-repository.md)). The candidate URL is the GitHub repo. Its tags are its languages
   (`java`, `kotlin`, `csharp`…) and the topics that found it. Its facts carry `package`: languages, official,
   packages (ecosystem, name, version, publish date, licences), the main package's licence and release date, and
   `terms` (decision 9). Review items are ordered: official repos with a confirmed package first, then other
   official repos, then the rest. No package becomes a resource of its own.
7. **On accept, only the opening source's packages count.** The review item keeps the facts per finder; a finder
   that finds the item again with new facts refreshes its own entry. On accept, `package` is read only from the
   finder that opened the item, whose record is the one written. So an item opened by the topic leaders is indexed
   without packages (Known limits). The `github-sdks` record gets the main package's licence (an SPDX expression; not
   `non-standard`, `NOASSERTION`, `NONE`) and `updated` = the main package's release date (checked as plausible). The
   packages stay in the record's `extra`. The resource takes licence and type only from records of its origin
   ([0004](../foundation/0004-persistence-and-the-schema.md)).
8. **Two policies.** GitHub calls go through `collector-github-sdks`, deps.dev calls through `collector-deps-dev`
   ([0006](../foundation/0006-outbound-calls-and-resilience.md)), so a slow deps.dev never opens GitHub's circuit. The deps.dev client is built from its own builder,
   without the GitHub token (a test checks no `Authorization` header goes there).
9. **Package names and languages are searchable.** The collector writes `package.terms`: the words of every package
   name, the registry's words (`maven`, `cargo crate crates`, `pypi pip`…), the languages (`csharp dotnet`, `go
   golang`), `library package` when there is a package, `sdk` for an official repo, and the other names of a
   provider the repo or a package names (`anthropic` → `claude`, from the compatibility list). The catalog keeps the
   distinct words of all records of a resource in `resource.package_terms` (at most 2,000 characters, rebuilt when
   records are written or forgotten), and `search_vector` adds them with weight B in the `simple` and `english`
   configurations ([0023](../search/0023-catalog-full-text-index.md)). The column is in `V1__schema.sql`.
10. **Backoffice only.** The collector runs in the back office. The public app only describes the source for the
    Sources page and reads the catalog.

### Code
- `backend/apps/backoffice/src/main/java/ai/helper2/sources/github/scout/GitHubSdkCollector.java`, `SdkSearchPlan.java`, `SdkFacts.java`, `SdkSearchTerms.java`
- `backend/apps/backoffice/src/main/java/ai/helper2/sources/depsdev/` (`DepsDevClient`, `PackageFinder`, `PackageSelection`, `NameGuesses`, `Ecosystem`, `PackageVersion`, `RegistryPackage`)
- `backend/apps/backoffice/src/main/java/ai/helper2/scout/internal/ResourceCandidateJson.java` (`package` per finder, read from the opening finder)
- `backend/apps/backoffice/src/main/kotlin/ai/helper2/scout/usecase/IndexAcceptedCandidate.kt` (`PackageFacts`)
- `backend/libs/catalog/src/main/java/ai/helper2/catalog/internal/JdbcCatalogWriter.java` (`refreshPackageTerms`)
- `backend/libs/schema/src/main/resources/db/migration/V1__schema.sql` (`resource.package_terms`, `search_vector`)
- `backend/libs/sources/src/main/java/ai/helper2/sources/config/CollectorDescriptors.java`, `CollectorProperties.java` (`GitHubSdks`)
- `backend/apps/backoffice/src/main/java/ai/helper2/sources/config/scout/CollectorsConfiguration.java` (`depsDevClient`, `reviewRoom`)
- `backend/apps/public/src/main/java/ai/helper2/sources/config/scout/ScoutSourceDescriptors.java`

## Alternatives considered
- **A: extend the topic leaders with a language list.** One collector less, but 18 % of the known SDKs, and one
  floor for all. Rejected on the numbers.
- **B: provider orgs only.** 55 %, all official, but no community library (go-openai, Rig, Jlama, langchaingo).
- **C (chosen): topics × languages and provider orgs, then deps.dev.** 79 % found, 66 % mapped, 74 % with the
  name guess. One new collector and one adapter; one new column, no new table.
- **Live search per registry (Maven Central, PyPI, crates.io…) per user query.** Dropped: PyPI has no search API,
  each registry ranks differently, and every query would wait on several more calls.
- **ecosyste.ms instead of deps.dev.** It covers PHP, Dart, R and Swift too, but its data is CC-BY-SA 4.0
  (share-alike on a derived catalog) and "commercial use" is not defined. It waits for a lawyer.
- **Libraries.io.** Its API needs a key and its data is CC-BY-SA 4.0 too.
- **A query synonym `claude` ↔ `anthropic` instead of package terms.** One line, but it changes every query with
  either word; package terms change only the resources that have packages.

## Consequences
- An official SDK reaches the review list with its install name and licence: `com.anthropic:anthropic-java`,
  Apache-2.0, released 6 Nov 2026. The admin sees what to accept.
- "java sdk for claude" finds `anthropics/anthropic-sdk-java` with every word, and "rust llm library" finds a repo
  whose crate and language say Rust (`PackageTermsSearchIT`). The frozen eval snapshots hold no `package_terms`, so
  `evalTest` cannot show the effect. It needs a new recording; no number is claimed here.
- About 1,400 repos a first pass; the review list skips those already listed or proposed, a repo listed only as the
  repository of a package included. That check reads `source_record.repository_url`, which has no index: one lookup per
  AI repo of the pass. At 500 new a day the first pass fits in a few days. Bulk accept does not cover resource
  candidates ([0038](../backoffice/0038-back-office-and-review.md): bulk accept is only for enrichment checks; bulk reject works): every candidate is accepted one
  by one.
- GitHub: up to 3 pages a query, 2 s apart, so a run of 20 queries takes up to 2 minutes. deps.dev: 1 call per new
  repo plus 2 per kept package plus up to 6 for a guess, once per run interval. No published rate limit; caching is
  expressly allowed.
- An unscoped package that starts with the repo name and copies its metadata (`ollama-js-junaid`) still passes; the
  back-link cannot tell it apart. The admin sees it before accepting.

### Known limits
- The frozen eval snapshots hold no package terms: the effect on search is not measured until a new snapshot set is recorded and graded ([S10.4.2](https://github.com/silvioincalza/helper2.ai/issues/348)).
- Bulk accept does not cover resource candidates: each is accepted one by one.
- An item the topic leaders opened first is indexed without packages: a later `github-sdks` record does not join it.
- PHP, Dart, R and Swift have no package source: ecosyste.ms waits for a lawyer ([S2.15.1](https://github.com/silvioincalza/helper2.ai/issues/220)).
- SDKs inside monorepos (AWS Bedrock runtime, Azure OpenAI) and repositories with no AI word (cohere-python) are not found.

### STRIDE
| Threat | Answer |
|---|---|
| Spoofing | GitHub and deps.dev over TLS. A package counts only when its latest version points back to the repo. An unverified package must sit in a scope or group near the repo's owner or name, or carry the repo's name; a re-publish under another scope (`@depup/openai`) is dropped. An unscoped name that starts with the repo name and copies its metadata can still pass: a person checks before accepting. |
| Tampering | Repo descriptions and package names reach the review list as text; nothing is indexed without a person. A licence that is not an SPDX expression, or `non-standard`, is dropped at accept. Search terms are words only (`[a-z0-9]`), at most 2,000 characters. |
| Repudiation | The accepted record keeps its source id and the `package` fact; the admin audit logs the decision. |
| Information disclosure | Only public repo owner and repo names and package names go to deps.dev, from the server. An owner can be a person (Privacy page). The deps.dev client has no `Authorization` header: the GitHub token never leaves for deps.dev (tested). |
| Denial of service | 20 queries a run, up to 3 pages each, 2 s apart, a stop when the shared token runs low, a 7-day pass; deps.dev only for new repos under the day's cap, once per run interval; 404s are not failures; circuit breaker and retry per policy. |
| Elevation of privilege | No new permission; the existing read-only PAT; no new endpoint. |

### AI architecture check
| # | Layer | State |
|---|---|---|
| 1 | Model | Covered: no model call. |
| 2 | Agent Framework | Deferred, safe: no agent. |
| 3 | Orchestration | Covered: a Scout pass with a cursor (index of the next query), a partial run when the token runs low, a checkpoint at the end; `held-back` starts the plan again and keeps the packages already read. |
| 4 | Tool & API | Covered: GitHub search (paged) and deps.dev v3, each under its policy. |
| 5 | Memory | Covered: packages per repo kept in memory for one run interval (at most 5,000 repos); nothing about users. |
| 6 | Knowledge & RAG | Covered: the catalog gains libraries and SDKs per language, and package names and languages in the search text. |
| 7 | Data | Covered: one column (`resource.package_terms`) in V1, derived from the records; `package` in the record's `extra`; provenance per package (deps.dev relation and the back-link check). |
| 8 | Browser & Computer Use | Deferred, safe: no page is fetched. |
| 9 | Guardrails | Covered: AI word list, star floors, no forks or archived repos, look-alike guard, back-link check, SPDX check, a person decides. |
| 10 | Observability | Covered: run records as for other collectors; `helper2.scout.sdks.unread` by reason; a deps.dev failure counts as `helper2.source.items.dropped{reason=packages-unavailable}`. |
| 11 | Evaluation | Covered: lib11–lib14 with grades; the frozen replay cannot show package terms, a new recording is needed. |
| 12 | Deployment | Covered: settings with defaults in both apps, no chart change. Rollback: `H2_SOURCE_GITHUB_SDKS_ENABLED=false`. |
| 13 | Security & Identity | Covered: no new secret; the GitHub token stays in OpenBao and off the deps.dev client. |
| 14 | Human-in-the-Loop | Covered: every repo waits for a person; official repos with a confirmed package come first. |
| 15 | Cost & Reliability | Covered: no credits; GitHub searches spaced and stopped on a low token; deps.dev only for new repos; separate circuit for deps.dev. |

### Points for a lawyer
- deps.dev: use is under the Google APIs Terms of Service; data deps.dev generates (the repo ↔ package links) is
  CC-BY 4.0; data from the registries is under each registry's own terms. Confirm that showing a package name,
  version, date and licence with a link, and the attribution on the Sources page, is enough.
- deps.dev as a recipient: Google LLC (United States) receives the repo owner and repo names we look up. An owner
  can be a person, so this is personal data sent abroad. Confirm the legal basis (legitimate interest in describing
  public repositories) and the transfer basis (Google is certified under the Data Privacy Framework), and the
  wording on the Privacy page.
- ecosyste.ms (later): CC-BY-SA 4.0 on a derived catalog (does share-alike reach the whole catalog?), and its
  undefined "commercial use". Ask ecosyste.ms (support@ecosyste.ms) before any use.

## Diagrams
[flow](../../diagrams/flow-0018-package-catalog.svg) · [sequence](../../diagrams/sequence-0018-package-catalog.svg)

## Backlog
- Decides: [F2.8](https://github.com/silvioincalza/helper2.ai/issues/38).
- Open issues that change it:
  - [S2.15.1](https://github.com/silvioincalza/helper2.ai/issues/220): PHP, Swift, C, C++, Elixir and Dart as platforms.
