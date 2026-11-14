# 0020 — Resource vitality

**Depends on:** [0012](0012-scout-collectors-and-events.md), [0022](../search/0022-federated-search.md), [0025](../search/0025-ranking.md)

## Context
Helper2.ai is a helper: a result must be something the user can use or build with today. On 11 Nov 2026 most
Hugging Face Spaces in the results did not run. The rules that say "this resource is alive" were in eight places,
each with its own numbers: the GitHub query (`fork:false archived:false`), the Spaces adapter (stage and likes), the
trending collector (20 likes), the topic leaders (3,000 stars), the SDK plan (stars per language), the awesome
lists (archived, fork, stars). npm, Hugging Face models and PyPI had no rule. A rule in an adapter is not a rule of
the product: the next source does not get it.

## Decision
1. **Sources state facts.** A hit carries `Vitality`: `archived`, `fork`, `deprecated`, `running`. Each is true,
   false or not known. Stars, likes, downloads and the last update stay where they are: the hit's popularity and
   its `updated` date. An adapter maps what its source states and decides nothing.
2. **One rule decides** (`VitalityPolicy`, pure domain code, no clock, no I/O). Two answers:
   - **Refused: not a result.** Only on a fact the source states.

     | Fact | Refusal |
     |---|---|
     | `archived` is true | `archived` |
     | `deprecated` is true | `deprecated` |
     | `running` is false (a hosted app whose state is known) | `not-running` |
     | `running` is true and fewer likes than `H2_SOURCE_HF_SPACES_MIN_LIKES` (3) | `too-few-likes` |
     | `fork` is true, fewer stars than `H2_VITALITY_FORK_MIN_STARS` (10) and no update inside `H2_VITALITY_MAINTAINED_WITHIN` (1,095 days) | `idle-fork` |

     A fork with stars, or a maintained fork, is a result: sometimes the fork is the living project.
   - **Unmaintained: a result that ranks lower.** An MCP server, library, skill or agent whose last update is
     older than `H2_VITALITY_MAINTAINED_WITHIN`. An unknown date says nothing. A paper, news, a product or a
     dataset is never unmaintained, and neither is a model: its weights are finished work (§8). Neither is a repository with `H2_VITALITY_ESTABLISHED_STARS` (10,000) stars or
     more: a work that many people starred and nobody needs to change is finished, not dead.
   Few stars or few downloads refuse nothing: a new, good project has few. They already count in the popularity
   term of [0025](../search/0025-ranking.md) §6.
3. **The rule runs where hits come in.**
   - Live search: `SearchResources` applies it to each source's answer, before the cache and before the limit
     (`H2_SEARCH_PER_SOURCE_LIMIT`). The admitted hits get consecutive ranks, so a refused hit does not push the
     others down. A source may answer with more hits than the limit.
   - Scout: `RunScout` asks the admission rule (§11), which asks this rule first, for each hit of a collector's
     batch before the catalog, and for each candidate before the review list. A candidate states what its source says about it as `CandidateSignals` (stars, last push,
     archived, fork; each known or not). A refused candidate is not proposed: a person does not review a dead
     repository. A candidate that states nothing is proposed.
   - Each refusal is counted in `helper2.source.items.dropped{source,reason}`.
4. **Rank penalty** ([0025](../search/0025-ranking.md)). An unmaintained result loses 0.008 (`RankingWeights.unmaintained`):
   as much as the strongest popularity, about half a first-place vote. It stays above a result that one source
   fewer found.
5. **Sources on the rule.** `hf-spaces` states `running` (stage `RUNNING` or `RUNNING_BUILDING`) and returns
   every Space the Hub lists; it has no filter of its own. `github` asks for forks too (`fork:true archived:false`) and
   states `fork` and `archived`. Checked on 11 Nov 2026 on 6 queries, 30 repositories each: 0 or 1 fork per query,
   each with more than 200 stars. `github-awesome` states `archived` and `fork` of every repository a list links
   and drops neither itself: an active fork found in a list is indexed, an archived repository is refused by the
   rule and counted. Its 50 stars (`min-repo-stars`) stay: they say what the collector fetches, not what is alive.
6. **What the live search cannot know.** The GitHub search states stars, fork, archived and the last push, not the
   last release: that is one more call per repository. The last release is read in the Scout, which opens the
   repository anyway.

7. **The Scout visits the repository.** In the live search a package or a model has a link, not the facts of what
   is behind it. The Scout follows the link when it enriches a resource
   ([0014](0014-evidence-gated-enrichment.md) §12, §13): it reads the README and, in the same GitHub call
   that already reads the licence, whether the repository is archived. It follows the repository of a GitHub record,
   of an MCP Registry entry whose namespace owns it, and of an `npm` or `pypi` record that links one.
   - The fact is stored on the source record (`extra.vitality`: `archived`, `checkedAt`), with no schema change. A
     later live observation of the same record keeps it while the record links the same repository; a record that
     now links another repository drops it until its next visit.
   - **What a source states is stored too.** A record keeps every fact of vitality its source states (`archived`,
     `fork`, `deprecated`, `running`) with the time it was stated (`extra.vitality.statedAt`, the retrieval time of
     the hit; `JdbcCatalogWriter.putStatedVitality`). A source that states nothing leaves what is stored.
   - **Newest wins on `archived`**, the one fact both the source and the visit can state. A check of the repository
     newer than the source's statement stays (`archived` and `checkedAt`); a statement newer than the check
     takes its place, and `checkedAt` goes. A statement that says nothing of `archived` keeps the check. The visit
     follows the same rule: it does not overwrite an `archived` a source stated after it, and it keeps the other
     facts (`JdbcEnrichmentCandidates.recordArchived`).
   - `helper2-index` and `helper2-semantic` read the stored facts of their rows in one query and state them on
     their hits; the rule of §2 then refuses an archived, deprecated or not running resource, a Space with too few
     likes and an idle fork, like any other hit. A resource of several records is archived, a fork or deprecated
     when one record states it, and running only when every record that states it says so.
   - Only `archived` is taken from a repository that a package merely links. Stars and `fork` are not: a package
     that links a famous repository would borrow its stars.
   - A repository that is no longer archived is admitted again at the next visit.

8. **The numbers, measured on 11 Nov 2026.**
   - **Maintained within 1,095 days (36 months).** The 707 GitHub repositories of the graded results
     (`docs/eval/search-qrels.tsv`), with the day of their last push read from GitHub:

     | No push for | good (grade 2–3, 219) | weak (grade 1, 345) | bad (grade 0, 143) |
     |---|---|---|---|
     | 12 months | 20.1 % | 45.5 % | 23.1 % |
     | 24 months | 7.3 % | 27.0 % | 16.8 % |
     | 36 months | 1.8 % | 13.6 % | 9.1 % |
     | 48 months | 0.5 % | 5.2 % | 7.0 % |

     At 24 months 16 good results would rank lower, `facebookresearch/segment-anything` (55,000 stars) among them: a
     reference work is finished, not dead. At 36 months 4 would, and a weak result is still 7.6 times as likely to
     be hit as a good one.
   - **Archived refuses.** 1 of the 707 is archived, and it is no good result.
   - **A fork needs 10 stars or a recent update.** Not measurable here: 1 fork in 707 (no stars, pushed this year,
     graded good, so it is admitted as maintained). The live search returned 0 or 1 fork in 30 results on 6
     queries. The number stays a first value; it decides almost nothing.
   - **A hosted app needs 3 likes.** On the 22 English "use it" queries of the eval set, asked most liked first:
     a minimum of 1, 3 or 5 leaves a Space on the same 4 queries; 0 adds 4 queries, each with Spaces nobody liked;
     10 loses one query. On 14 of the 22 the Hub returns no Space at all for the words: the minimum is not what
     hides the good Spaces, the word search is.
   - **A model is never unmaintained.** Of the 100 most liked models on the Hub, 6 were last changed over 36
     months ago, `stabilityai/stable-diffusion-xl-base-1.0` and `lllyasviel/ControlNet` among them: reference
     models, not dead ones.
   - **The last release is no live signal.** 333 of the 707 repositories have a GitHub release. The last push is
     known for all.
9. **Hugging Face models.** `hf-models` asks the Hub for `disabled` (and `gated`, not used) with the fields
   it already reads, and states a disabled model as `deprecated`: it is refused. It also asks for `lastModified`,
   which the Hub's listing does not return unasked: a live model hit has its update date, so the "updated
   after" filter keeps it.
10. **MCP Registry.** A server whose latest version has the status `deprecated` is stated as `deprecated` and the
    Scout does not index it. A `deleted` one is withdrawn. A server deprecated after it was indexed stays
    in the catalog until the sweep of [S2.11.4](https://github.com/silvioincalza/helper2.ai/issues/216).

11. **Admission: one rule says what the Scout indexes without a person** (`AdmissionPolicy`, package
    `ai.helper2.scout.admission`, pure domain code). A collector states what it found; it does not decide what enters
    the catalog. For a hit of a collector the rule gives one of three outcomes:

    | Outcome | When | What the Scout does |
    |---|---|---|
    | **Refuse** (with the reason) | Only on a fact the source states: the rule asks `VitalityPolicy.refusalOf` (§2) | Not indexed, not proposed, counted in `helper2.source.items.dropped{source,reason}` |
    | **Review** (with the reason) | The hit is under the index floor of its collector | Not indexed without a person. It reaches the review list only as a candidate its collector proposes |
    | **Index** | Everything else | Indexed |

    - **The floors are per collector profile, in one place** (`AdmissionRules`, one `IndexFloor` per source id; the
      numbers in `helper2.scout.admission`, record `ScoutAdmissionProperties`). A repository of a curated list needs
      fewer stars than a repository of a topic search, so the numbers are not per resource type.

      | Collector | Floor to be indexed without a person | Reason to review |
      |---|---|---|
      | `github-topic-leaders` | `H2_SCOUT_TOPIC_LEADERS_INDEX_MIN_STARS` (1,000) stars and a push inside `H2_SCOUT_TOPIC_LEADERS_INDEX_PUSHED_WITHIN` (90 days); an unknown push date is not a push | `few-stars`, `not-active` |
      | `hf-trending` | 20 likes (`index-min-likes`) | `few-likes` |
      | `pypi` | a GitHub repository and a summary | `no-repository`, `no-description` |
      | every other collector | none: the vitality rule alone | |

    - **A candidate always goes to a person.** For a candidate the rule only refuses: a candidate under the floor
      is proposed, that is what the review list is for.
    - **PyPI asks the same rule to choose the shape.** Only the collector holds the row a hit or a candidate is built
      from, so `PypiCollector` asks `AdmissionPolicy.indexes` and builds a hit or a candidate. The rule stays in one
      place.
    - **What stays in the adapters is collection scope**: filters that choose what to fetch, not what to admit. They
      save calls. The GitHub search filters (`stars:>=N pushed:>=… fork:false archived:false` of the topic leaders,
      which reads its numbers from the floor above; the star floors per language of `SdkSearchPlan`; the list
      search of the awesome lists), `min-repo-stars` of the awesome lists, the nested-list rule of `AwesomeCrawl`,
      the 30-day window of the PyPI candidates, the per-topic and per-day limits.

### Code
- `backend/libs/search/src/main/kotlin/ai/helper2/search/Vitality.kt` — `Vitality`, `VitalityRules`, `Refusal`, `VitalityPolicy`.
- `backend/libs/search/src/main/kotlin/ai/helper2/search/usecase/SearchResources.kt` — `admitted`.
- `backend/libs/search/src/main/kotlin/ai/helper2/search/Ranking.kt` — `RankingWeights.unmaintained`.
- `backend/apps/backoffice/src/main/kotlin/ai/helper2/scout/admission/Admission.kt` — `AdmissionPolicy`, `Admission`
  (`Index`, `Review`, `Refuse`), `AdmissionRules`, `IndexFloor`, `ResourceSignals`, `ReviewReason`.
- `backend/apps/backoffice/src/main/java/ai/helper2/scout/config/ScoutAdmissionProperties.java` — `helper2.scout.admission`;
  `scout/internal/ScoutConfiguration.java` — `admissionRules`, `admissionPolicy`.
- `backend/apps/backoffice/src/main/kotlin/ai/helper2/scout/usecase/RunScout.kt` — `indexable`, `proposable`.
- `backend/apps/backoffice/src/main/kotlin/ai/helper2/scout/Ports.kt` — `CandidateSignals.statedOn`;
  `sources/github/scout/RepositorySignals.java` — what GitHub states about a candidate.
- `backend/libs/search/src/main/java/ai/helper2/search/config/VitalityConfiguration.java`, `VitalityProperties.java` — `helper2.vitality`.
- `backend/libs/sources/src/main/java/ai/helper2/sources/huggingface/HuggingFaceSpacesSource.java`, `github/GitHubSource.java` — the facts.
- `backend/apps/backoffice/src/main/kotlin/ai/helper2/scout/GapRound.kt` (`GapTargets.select`, `namesPackage`),
  `usecase/EnrichResource.kt` (`rememberIfArchived`), `sources/evidence/ReadmeEvidence.java` — the visit.
- `backend/libs/catalog/src/main/java/ai/helper2/catalog/internal/JdbcEnrichmentCandidates.java` (`recordArchived`),
  `ResourceRows.java` (`statedVitality`), `CatalogSearchSource.java`, `JdbcCatalogWriter.java` — the stored fact.
- Tests: `VitalityPolicyTest`, `RankingTest`, `SearchResourcesTest`, `HuggingFaceSourcesTest`, `GitHubSourceTest`,
  `RunScoutTest` (an archived candidate is not proposed; a hit under its floor is not indexed), `AdmissionPolicyTest`
  (one row per rule and per collector), `ScoutAdmissionRulesTest`, `GitHubAwesomeCollectorTest` (an active fork of a list is
  indexed, an archived repository is refused and counted), `JdbcCatalogWriterIT`, `JdbcEnrichmentCandidatesIT`,
  `CatalogSearchSourceIT` (the stored facts and which one wins).

## Alternatives considered
- **Rules in each adapter** — eight places, eight sets of numbers, no rule for a new source.
- **A minimum of stars or downloads as a refusal** — hides new projects; popularity already ranks them lower.
- **Refusing every fork** — simple, but hides a fork that became the living project.
- **Reading the last release in the live search** — one more call per repository inside a 2.5 s budget.
- **The admission rule next to `Vitality.kt` in the search library** — the live search does not admit to the
  catalog; only the Scout indexes without a person. **In the catalog library** — the catalog would hold Scout rules.
- **Admission numbers per resource type** — a repository of a curated list and one of a topic search need different
  numbers; the collector says where a resource comes from.
- **One strategy class per resource type, a `Resource` aggregate** — four rules today; one rules record and one
  function are enough.
- **A quality score from a model** — cost per result, not repeatable, hard to explain.

## Consequences
- A new source gets the rule by stating facts. A new fact (a yanked release, a disabled model) is one field and
  one line of the rule.
- A resource is visited when the Scout enriches it and its records left a field unproven, inside the daily
  budget of the gap round. A resource whose records fill every field is not visited, and the catalog is not swept:
  an archived repository stays a result until its turn comes.
- Each catalog search reads the stored facts of its rows: one more query by `resource_id` (indexed), not
  measured.
- A stored fact is as old as its record. A Space stored as not running, or a model stored as deprecated, stays
  refused from the catalog until its source states it again; a hit served from the search cache carries no fact of
  vitality (`SourceHitCodec`), so it stores none and leaves what is stored.
- A statement of the source read back from a record and written again counts as a statement at the record's
  retrieval time: when that is after the Scout's check, the same `archived` value stays, without `checkedAt`.
- No collector decides what is indexed without a person: the floors of the topic leaders, the trending models and
  PyPI are one rule with one set of numbers per collector (§11). What a collector still filters is collection
  scope, and it is named so.
- A hit under its floor is not counted as dropped: nothing is wrong with it. The run log says how many hits of a
  collector were left to a person, by reason. `itemsSeen` of a run counts what the collector read, floors or not.
- The topic leaders collector does not check a repository again in code; the Scout does, on the hit. A repository
  the GitHub search returns against its own filter still is not indexed.
- An archived repository of a list is classified before it is refused: a few more classifier calls per pass.
- The user does not see why a result ranks lower: the API and the card have no "not maintained" label.
- The rank penalty is not measured: the frozen snapshots hold no dates, so the variant `unmaintained-kept` reads
  0.000. The fork and Space rules are not in the snapshots either.

### Known limits
- The effect of the rank penalty on nDCG is not measured: the eval snapshots hold no dates ([S2.11.3](https://github.com/silvioincalza/helper2.ai/issues/215)).
- The Scout visits only the resources it enriches, so an archived repository stays a result until its resource is enriched ([S2.11.4](https://github.com/silvioincalza/helper2.ai/issues/216)).
- Hugging Face `gated`, npm `deprecated`, PyPI `yanked` and a product homepage that no longer answers are not read ([S2.11.3](https://github.com/silvioincalza/helper2.ai/issues/215)).

### AI architecture check
- Covered: Tool & API (adapters state facts), Data (no schema change; the facts are stored in `extra.vitality`), Guardrails (dead
  resources do not reach the guide's retrieval), Observability (refusals counted by source and reason), Evaluation
  (variant `unmaintained-kept`; not measured, Known limits), Cost & Reliability (no extra call on the request path).
- Covered: Human-in-the-Loop ("indexed without a person" is one rule; a candidate always reaches a person unless a
  stated fact refuses it).
- Not touched: Model, Agent Framework, Orchestration, Memory, Knowledge & RAG, Browser & Computer Use, Deployment,
  Security & Identity.

## Diagrams
[flow](../../diagrams/flow-0020-resource-vitality.svg) ·
[sequence](../../diagrams/sequence-0020-resource-vitality.svg)

## Backlog
- Decides: [F2.11](https://github.com/silvioincalza/helper2.ai/issues/41).
- Open issues that change it:
  - [S2.11.3](https://github.com/silvioincalza/helper2.ai/issues/215): every source and collector states its vitality facts.
  - [S2.11.4](https://github.com/silvioincalza/helper2.ai/issues/216): the Scout visits every record that has a repository link.
