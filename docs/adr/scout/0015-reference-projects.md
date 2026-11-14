# 0015 — Reference projects

**Depends on:** [0006](../foundation/0006-outbound-calls-and-resilience.md), [0011](0011-scout-runner.md), [0012](0012-scout-collectors-and-events.md), [0013](0013-discovery.md), [0046](../operations/0046-evaluation-guide-and-search.md)

## Context
The first graded search set ([0046](../operations/0046-evaluation-guide-and-search.md), 20 queries) showed that most misses are not a ranking problem. In 14 of 20
queries the answer an expert gives first was in no source's list and not in the catalog: LangGraph and CrewAI for
"library to build AI agents", LlamaIndex for RAG over PDFs, Rasa and ChatterBot for chatbots, Whisper, SAM,
bge-reranker, Spring AI. A search only finds what a live source returns for the words typed, or what the catalog
already holds. The catalog only holds what a search or a collector saw.

The official MCP Registry lists Atlassian's server (`com.atlassian/atlassian-mcp-server`), and the Scout reads the
registry. A reader that asks for every version and stops at the run's item budget does not reach it:
- The registry holds over 30,000 servers (9 Nov 2026), returned in name order.
- With every version asked for, about 45 % of each page is old versions.
- At the run's item budget, five runs reach about the 3,000th server (`app.flamap/flamap`); `com.atlassian` is
  about the 6,250th.

So the registry's first pass would take a month, and servers late in the alphabet wait for weeks.

### Measurement
A probe on 9 Nov 2026 with the 12 default topics at 3,000 stars or more returned 624 distinct repositories. It
found 13 of 21 reference answers of the graded set: LangGraph, CrewAI, LlamaIndex, RAGFlow, Haystack, AutoGen,
Rasa, ChatterBot, ollama/ollama, LangChain, Transformers, Outlines, faster-whisper. It missed Instructor, Spring AI,
smolagents, PEFT, Whisper, SAM, SAM 2 (no matching topic at that floor) and Atlassian's server (decision 1 covers
it). How many of the 624 already sit in the catalog is not measured: the review list skips those.

GitHub's counts on 11 Nov 2026 for the 14 default topics (not forks, not archived, pushed since 11 Aug 2026), before
removing the repositories several topics share: 4,375 with 1,000 stars or more, no topic over 1,000 (the largest:
`llm` 967, `mcp` 675, `ai-agents` 674, `machine-learning` 576). With more than 100 stars the same search counts
14,593, and five topics pass the 1,000 results GitHub returns for one search. On staging that day, 249 of the 321
names that relationships pointed at were in no catalog resource: PyTorch 29 times, TensorFlow 12.

## Decision
1. **The MCP Registry is read to the end.** The collector asks for `version=latest`, so every row counts. It reads
   up to `items-per-run` (5,000, `H2_SCOUT_MCP_ITEMS_PER_RUN`) servers a run, when that is more than the run's own
   budget. Registry entries are cheap to index: no page read and no model call of their own. A first pass
   takes about a week.
2. **GitHub topic leaders, for review.** A collector, `github-topic-leaders`, searches GitHub once per topic for
   the most-starred repositories: `topic:{t} stars:>=3000 fork:false archived:false`, sorted by stars, 50 per
   topic. 14 topics by default (`H2_SCOUT_TOPIC_LEADERS_TOPICS`): the AI topics, plus `machine-learning` and `deep-learning` so the frameworks under them (PyTorch, TensorFlow, scikit-learn) reach the review list. A pass runs every 7 days.
   - These leaders are not indexed by this search. Each one is proposed to the admin review list. The review list
     skips a repository already in the catalog or proposed before, whatever was decided then ([0013](0013-discovery.md)), so a
     leader decision 3 indexed opens no item.
   - A repository that leads several topics is one candidate, tagged with each topic (at most 12 tags).
   - The candidate's type comes from its name, description and topics by the fixed rules (`Classification.typeOf`),
     library when nothing says otherwise. The admin can change it before accepting ([0038](../backoffice/0038-back-office-and-review.md)).
   - The candidate states its stars, last push, archived and fork as signals (`CandidateSignals`), so the accepted
     record keeps them ([0013](0013-discovery.md) §9), and keeps its topics as a fact (`topics`).
   - At most `per-day` (25) new review items a UTC day ([0013](0013-discovery.md)). When the cap holds back a repository that is
     not in the catalog and was never proposed, the pass stays open: the next daily run searches again and opens
     the next 25. The pass closes, and its 7 days start, once a run holds nothing back.
   - It runs only with `H2_GITHUB_TOKEN`: the searches of a pass need the token's rate limit.
   - Calls go through the policy `collector-github-topic-leaders` ([0006](../foundation/0006-outbound-calls-and-resilience.md)).
3. **An active leader is indexed without a review.** A second search per topic asks for the repositories a person
   would accept without reading: `topic:{t} stars:>=1000 pushed:>={today − 90 days} fork:false archived:false`,
   sorted by stars, 100 a page, up to 1,000 a topic (GitHub returns no more for one search).
   - The rule is the index floor of `github-topic-leaders` in the admission rule of the Scout
     ([0020](0020-resource-vitality.md) §11): at least `H2_SCOUT_TOPIC_LEADERS_INDEX_MIN_STARS` (1,000)
     stars and a push inside `H2_SCOUT_TOPIC_LEADERS_INDEX_PUSHED_WITHIN` (90 days). The search filter reads the same
     two numbers: it is collection scope, it saves calls. `RunScout` asks the rule for every hit: a repository with
     no push date, or one the search returns against its filter, is not indexed.
   - **The topic alone is no proof.** A repository is indexed only when something beside the topic it was found under
     says it is about AI: its name, its description or another of its topics (`Classification.isAiRelated`, the
     words the live GitHub search uses). On 11 Nov 2026, of the first 100 repositories of each search: `chatbot` 14
     with nothing else about AI (`python-telegram-bot`, 29,576 stars, a wrapper of the Telegram Bot API;
     `Red-DiscordBot`), `mcp` 3 (`drawdb`, a database diagram editor), `agents` 3, `rag` 1. Such a repository is
     not indexed (counted `not-ai` in `helper2.source.items.dropped`); with 3,000 stars it is still proposed for
     review, where a person decides.
   - A repository that passes is a hit of the source `github-topic-leaders`, mapped like a GitHub search hit
     (`GitHubRepositories.hit`): type by the fixed rules, licence, stars as popularity, created and pushed dates,
     `archived` and `fork` for the vitality rule ([0020](0020-resource-vitality.md)). The Scout indexes it
     like any collector's hit.
   - A leader of decision 2 that is not in the catalog still goes to the review list: a famous quiet project
     (3,000 stars or more, no push for 90 days) gets a person's look, and so does an active one the per-topic limit
     left out. The collector does not guess which ones were indexed: the hits of a run are indexed before its
     candidates are proposed (`RunScout.collect`), and the review list asks the catalog.
   - A run indexes no more than its item budget, and a topic gives no more than its limit, also inside a page. The
     run saves its place (`topic:page`, and how many repositories of that page it took); the next daily run reads
     the same page again and takes the rest. The review
     search of decision 2 runs once the indexing has read every topic. A pass the review cap held back asks only for
     the review list again.
   - Searches are `H2_SCOUT_TOPIC_LEADERS_SEARCH_PAUSE` (2 s) apart: GitHub allows 30 searches a minute.
   - `H2_SCOUT_TOPIC_LEADERS_INDEX_PER_TOPIC=0` turns the rule off: every leader waits for a person again.

### Code
- `backend/apps/backoffice/src/main/java/ai/helper2/sources/github/scout/GitHubTopicLeadersCollector.java` (`Indexing`: the search scope, `Place`)
- `backend/apps/backoffice/src/main/kotlin/ai/helper2/scout/admission/Admission.kt` (`AdmissionPolicy`, the index floor)
- `backend/libs/core/src/main/java/ai/helper2/sources/github/GitHubRepositories.java` (`hit`, shared with `GitHubSource`)
- `backend/apps/backoffice/src/main/java/ai/helper2/sources/mcp/McpRegistryCollector.java`
- `backend/libs/core/src/main/java/ai/helper2/sources/config/CollectorDescriptors.java`, `CollectorProperties.java`
- `backend/apps/backoffice/src/main/java/ai/helper2/sources/config/scout/CollectorsConfiguration.java`
- `backend/apps/public/src/main/java/ai/helper2/sources/config/scout/ScoutSourceDescriptors.java`
- `backend/apps/backoffice/src/main/java/ai/helper2/scout/internal/ReviewQueueCandidateReview.java`, `backend/apps/backoffice/src/main/kotlin/ai/helper2/scout/usecase/RunScout.kt` (`cursorAfter`)
- `backend/libs/core/src/main/java/ai/helper2/catalog/ReviewQueue.java` (`candidateKnown`)

## Alternatives considered
- **A curated seed file of reference projects.** Complete for the names on it and tiny, but it is one more list to
  keep by hand, it reflects one person's view, and it never finds a new project. Kept as a fallback for gaps the
  topics cannot reach (Spring AI, Instructor).
- **Awesome lists with a stars floor** ([0013](0013-discovery.md)). Already running; its entries are indexed directly, and its reach
  depends on which lists exist and how far a pass gets. A stars-ordered pass would change a large collector.
- **Index every topic leader directly.** Popular repositories are not always AI tools. Kept for the active ones
  only: reading `pytorch/pytorch` before accepting it adds nothing, and the review list held hundreds of them while
  relationships pointed at names the catalog did not hold.
- **A floor of 100 stars.** 14,593 results, five topics over GitHub's 1,000 a search, and many course notes and
  notebooks under `machine-learning` and `deep-learning`.
- **Send the leaders with 100 to 999 stars to review.** Thousands of items for one person.

## Consequences
- Active reference projects reach the catalog with no click, no model call and no credits. The others after one
  click each.
- The first pass indexes a few thousand repositories over several daily runs (1,000 items a run), so the
  embedding indexer and the enrichment backlog grow by as many.
- Nobody reads the type of an indexed leader. The fixed rules type it, library when nothing says otherwise; a course
  or a list of notebooks with an AI topic and 1,000 stars is indexed as a library until a rule or an admin corrects it.
- A pass makes up to 10 searches a topic plus one for the review list: at most 154 calls, about 5 minutes of pauses.
- The review list gets fewer leaders: only the quiet ones. The cap and the floors are settings.
- The registry's first pass indexes up to 5,000 servers a run, so the embedding indexer has a backlog for some
  hours ([0024](../search/0024-vector-search.md): 1 every 5 s, about 7 hours for 5,000).
- Models (Whisper, SAM, rerankers) and papers are not covered here (Known limits).

### Known limits
- Models (Whisper, SAM, rerankers) are not covered: no collector reads the most liked models per `pipeline_tag`.
- Seminal papers are not covered: no source is sorted by citations ([S3.13.1](https://github.com/silvioincalza/helper2.ai/issues/255)).
- A reference project no topic reaches is not found: there is no seed list for it.
- A topic leader is indexed without the "is it about AI" check ([S2.12.1](https://github.com/silvioincalza/helper2.ai/issues/217)).

### STRIDE
| Threat | Answer |
|---|---|
| Spoofing | GitHub over TLS with the read-only token; no inbound endpoint. |
| Tampering | A repository's own description is indexed as text when the repository has 1,000 stars and a recent push, like a GitHub search hit; it is shown escaped and reaches a model only through the evidence gate ([0014](0014-evidence-gated-enrichment.md)). Stars can be bought: the floor raises the price, it does not prove quality. The others wait for a person. |
| Repudiation | Each accepted candidate keeps its source id and facts; the admin audit logs the decision. |
| Information disclosure | Public repository metadata only; the token stays in OpenBao. |
| Denial of service | At most 154 calls a pass, 2 s apart, a 7-day interval (daily only while a pass is open), a run's item budget, circuit breaker and retry policy, per-day cap on review items. |
| Elevation of privilege | No new permission: the existing read-only PAT. |

### AI architecture check
| # | Layer | State |
|---|---|---|
| 1 | Model | Covered: no model call. |
| 2 | Agent Framework | Deferred, safe: no agent. |
| 3 | Orchestration | Covered: part of the Scout pass; the interval and the checkpoint as for other collectors; a pass the daily cap held back stays open until it is drained. |
| 4 | Tool & API | Covered: GitHub search API and MCP Registry API under their policies. |
| 5 | Memory | Deferred, safe: nothing about users. |
| 6 | Knowledge & RAG | Covered: the catalog gains the reference answers the eval found missing. |
| 7 | Data | Covered: no new table; review items with facts; no migration. |
| 8 | Browser & Computer Use | Deferred, safe: no page is fetched. |
| 9 | Guardrails | Covered: stars floors, a recent push, no forks, no archived repositories, the rule checked again in code, AI words beside the topic, the vitality rule on every hit, tag cap. |
| 10 | Observability | Covered: run records as for other collectors (seen, changed). |
| 11 | Evaluation | Covered: re-record the eval set after the first accepted batch; Recall@20 per group ([0046](../operations/0046-evaluation-guide-and-search.md)). |
| 12 | Deployment | Covered: settings with defaults, no chart change. Rollback: disable the collector, or items-per-run 0. |
| 13 | Security & Identity | Covered: no new secret, no new endpoint. |
| 14 | Human-in-the-Loop | Partly, on purpose: an active leader with 1,000 stars is indexed with no person; every other leader waits for one. Off with `H2_SCOUT_TOPIC_LEADERS_INDEX_PER_TOPIC=0`. |
| 15 | Cost & Reliability | Covered: no credits; at most 154 GitHub calls a pass; registry calls bounded per run. Each indexed repository later costs one embedding and one enrichment. |

### Points for a lawyer
- GitHub's terms allow API use; showing a repository's name, description and stars with a link is what the other
  GitHub collectors already do. No new point.

## Diagrams
[flow](../../diagrams/flow-0015-reference-projects-for-review.svg) · [sequence](../../diagrams/sequence-0015-reference-projects-for-review.svg)

## Backlog
- Decides: [F2.5](https://github.com/silvioincalza/helper2.ai/issues/35).
- Open issues that change it:
  - [S2.12.1](https://github.com/silvioincalza/helper2.ai/issues/217): `Classification.isAiRelated` measured, then applied to `github-topic-leaders`.
