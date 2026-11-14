# 0029 — Sources search content words

**Depends on:** [0022](0022-federated-search.md), [0023](0023-catalog-full-text-index.md), [0025](0025-ranking.md), [0046](../operations/0046-evaluation-guide-and-search.md).

## Context

Every live source got the visitor's sentence as typed. GitHub, Hugging Face, arXiv and npm require every word to
match. So "Python library to build retrieval-augmented generation over PDF files" found 0 GitHub repositories:
"library", "to", "build", "over" and "files" must all be in the repository's name, description or topics.
Hugging Face is worse: its `search` matches a part of the model id. "reranker model for retrieval" and
"open model for image segmentation" found no model at all.

The graded starter set (20 queries, 8-9 Nov 2026) showed the cost. 23 known answers were added by hand because no
run returned them: LangGraph, CrewAI, Instructor, Outlines, Spring AI, Whisper, SAM, bge-reranker and others.
19 of the 23 were returned by no source. Ranking cannot fix that: it orders what the sources give.

## Decision

1. **Content terms.** `QueryAnalyzer` gives `contentTerms`: the query's words without function words (English,
   Italian, Spanish, French, German), request words ("build", "create", "best", "open", "tool", "files"…) and type
   words ("library", "model", "paper"…). A type word stays when a content word follows it, as in
   "model context protocol". "server" next to "mcp" goes, as for the index. A query made only of such words keeps its
   words. A word keeps the punctuation of a name ("sentence-transformers/all-minilm-l6-v2", "3.1"): the Hub matches
   part of a model id. The lists are short, fixed and visible: the same query always gives the same terms.
2. **Which terms a source gets.** `SearchResources` puts `searchTerms` in the `SourceQuery`:
   the content terms of the English translation when there is one ([0023](0023-catalog-full-text-index.md)), else of
   the query. A query with lexicon terms but no translation keeps the old search (text and expansions), because its
   own words are not English. `SourceQuery.keywords` is the text a keyword engine searches.
3. **Sources.** GitHub, arXiv and npm search `keywords`; Hugging Face Spaces too when it lists by words, and the
   sentence when it searches by meaning ([0022](0022-federated-search.md) §13). Live web keeps the natural sentence:
   web search engines want it. The catalog sources are unchanged.
4. **Hugging Face models by task.** `HubTasks` maps task phrases to the Hub's `pipeline_tag`: "speech to text",
   "image segmentation", "reranker", "embedding", "text to image" and a few more. It looks for the phrase in the query,
   in its full English translation and in its content words, so "modelo de texto a voz" finds text-to-speech even though
   the content words drop "to". A query that names a task asks for
   that tag, most liked first, with the other content words as `search` ("speech-to-text" counts as the phrase). When the other words leave less than half a
   page, a second call adds the task's most liked models. A query without a task word searches its content words in
   the Hub's own order, in one call.
5. **"paper" is a type hint** (PAPER), like "library" and "model".

Two parts of the first plan were measured and left out (see Measurement): sorting by downloads, and filling a thin
page with single-word searches. Likes, not downloads, for task queries: downloads put converted copies and test
models first (whisperkit before Whisper, 8 Nov 2026).

### Code

- `backend/libs/search/src/main/kotlin/ai/helper2/search/QueryAnalyzer.kt` (`contentTerms`, `contentTermsOf`,
  word lists, "paper" type word), `Model.kt` (`SourceQuery.searchTerms`, `keywords`),
  `usecase/SearchResources.kt` (`searchTermsOf`).
- `backend/libs/sources/src/main/java/ai/helper2/sources/github/GitHubSource.java` (`searchText`),
  `huggingface/HuggingFaceModelsSource.java`, `HubTasks.java`, `HuggingFaceSpacesSource.java`,
  `npm/NpmSource.java`, `arxiv/ArxivSource.java`, `support/HttpClients.java` (`hubListing` without empty `search`).
- Tests: `QueryAnalyzerTest.kt`, `QueryTranslationTest.kt`, `GitHubSourceTest.java`, `HuggingFaceSourcesTest.java`,
  `HubTasksTest.java`.

## Alternatives considered

- **A model rewrites the query per source** — better for odd phrasings, but a model call on every search, slower and
  not deterministic. Rejected: the fixed lists close most of the gap.
- **GitHub `language:` qualifiers from language words** ("java" → `language:Java`) — tried live: mixed results
  (tutorial repositories first). Not taken.
- **OR between the words on GitHub** — GitHub allows 5 operators and ranks OR matches poorly. Not taken.
- **Most downloaded first and single-word fill on Hugging Face** — the first plan; measured worse (above).

## Consequences

- Descriptive queries reach GitHub and Hugging Face again; navigational queries ("ollama") are unchanged.
- The terms lists are code. A word added to them changes every search, so it goes through the eval gate.
- Typos are not fixed: "speach to text" names no task, so Whisper is still not found.
- arXiv's relevance has no citation signal, so seminal papers stay hard to find (open: OpenAlex, [#255](https://github.com/silvioincalza/helper2.ai/issues/255)).

### Measurement

Same-time A/B on 9 Nov 2026: for each of the 20 starter queries, GitHub, Hugging Face models and Spaces and npm were
called live with the old text and with the new terms, one right after the other. The other sources come from the
7 Nov snapshots. arXiv refused the calls (time-outs, then 429) from both machines, so its snapshot hits are kept in
both arms. Merge and ranking are the production ones, replayed by the checked replica of [0046](../operations/0046-evaluation-guide-and-search.md).
19 queries have a relevant grade. Interval: paired bootstrap, 10,000 resamples, 95 %.

| Arm | nDCG@10 | Δ vs old | 95 % interval | Recall@20 | MRR | Unjudged in top 10 |
|---|---|---|---|---|---|---|
| Old text (same time) | 0.385 | | | 0.440 | 0.527 | 6 % |
| **Content terms + task tags (this decision)** | **0.402** | **+0.016** | **[-0.006, +0.040]** | 0.379 | 0.544 | 16 % |
| Same, HF models most downloaded first | 0.392 | +0.007 | [-0.024, +0.036] | 0.369 | 0.544 | 18 % |
| Same, plus single-word fill (first plan) | 0.387 | +0.002 | [-0.031, +0.033] | 0.351 | 0.542 | 20 % |

- **Zero-result queries.** GitHub found nothing for 6 queries with the old text (lib01, lib05, lng03, lng07, mcp03,
  the nonsense query). With the terms it finds 30, 19, 30, 30, 2 and 0 repositories.
- **Known answers.** Of the 23 hand-added answers, the old arm returns 4 (none in the top 20). This arm returns 7,
  one in the top 20 (jina-reranker-v2 at #8 for "reranker model for retrieval"; Qwen3-Reranker and ms-marco
  cross-encoders above it). CrewAI and ChatterBot are found now, at #29 and #23.
- **Most downloaded first** hurts "stable diffusion xl" (0.722 → 0.538): test models with many downloads come first.
- **Single-word fill** brings models named "mixture" or "context" into paper and news queries, and 30 models for the
  nonsense query.
- **Recall@20 is lower because the new results are not graded yet**: 16 % of the new top 10 has no grade, and an
  ungraded result counts as not relevant. 86 new results in the top 20 (17 queries) wait in the judging pool. The
  interval contains 0, so the decision is accepted only after they are graded and the gate is run again.

### STRIDE

| Threat | Risk | Control |
|---|---|---|
| Spoofing | — | No new party: the same sources get fewer words. |
| Tampering | Query syntax injected into a source's search | GitHub qualifiers and arXiv syntax are cleaned as before; the terms are a subset of the query's words. |
| Repudiation | Not knowing why a source was searched with other words | The rule is deterministic and tested; the same query always gives the same terms. |
| Information disclosure | — | Sources get fewer of the visitor's words than before. |
| Denial of service | More calls per search | One extra Hugging Face call at most, only for a task query whose words leave a thin page; within the source's time limit and circuit ([0006](../foundation/0006-outbound-calls-and-resilience.md)). |
| Elevation of privilege | — | No new endpoint or role. |

OWASP LLM Top 10: no model is involved; a model-written translation only gives words, cleaned like the query.

### AI architecture check

| Layer | Status |
|---|---|
| Model | Deferred, safe: no new model call; the translation ([0023](0023-catalog-full-text-index.md)) is reused. |
| Agent framework, Memory, Browser & Computer Use, Human-in-the-loop | Deferred, safe: not involved. |
| Orchestration | Covered: same fan-out, deadlines and circuits. |
| Tool & API | Covered: same APIs; Hugging Face gets `pipeline_tag` and `sort=likes`. |
| Knowledge & RAG | Covered: this is a retrieval fix. Re-ranking is deferred until recall improves. |
| Data | Deferred, safe: nothing stored changes. |
| Guardrails | Covered: fixed word lists, cleaned input. |
| Observability | Covered: source spans and outcomes as before. |
| Evaluation | Gap, must fix: grade the 86 new pool results and run the gate again before acceptance. |
| Deployment | Covered: no setting, no migration. |
| Security & Identity | Covered: no change. |
| Cost & Reliability | Covered: at most one extra Hugging Face call; no metered source changes. |

### Privacy

No change to the Privacy page: the same processors get the query, now with fewer of its words.

### Open points

- The acceptance gate of this decision has not run: the new results are graded and the interval of the Measurement
  section holds ([0046](../operations/0046-evaluation-guide-and-search.md) §17) before the content words count as measured.
- The 86 new results are not graded and the gate has not run on them.
- No snapshot set is recorded with the content words.
- Task phrases are not typo-tolerant.

## Diagrams

[flow](../../diagrams/flow-0029-source-query-terms.svg) · [sequence](../../diagrams/sequence-0029-source-query-terms.svg)

## Backlog

- Features: [F3.8](https://github.com/silvioincalza/helper2.ai/issues/54) Sources search content words only.
- Open issues that change this decision:
  - [#255](https://github.com/silvioincalza/helper2.ai/issues/255) S3.13.1 Papers ranked by citations
  - [#348](https://github.com/silvioincalza/helper2.ai/issues/348) S10.4.2 The first ten results of the new snapshot set graded
