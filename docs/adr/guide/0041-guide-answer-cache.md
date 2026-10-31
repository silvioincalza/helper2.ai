# 0041 — Guide answer cache: normalised keys, answers in the shared cache, reuse for a near-identical question read the same way

**Status.** Accepted. **Builds on:** [0004](../foundation/0004-persistence-and-provenance.md), [0012](../search/0012-vector-search-pgvector.md), [0017](0017-guide-cost-guard.md), [0030](../quality/0030-guide-evaluation.md), [0032](0032-guide-intents.md), [0034](../operations/0034-data-retention.md), [0037](../search/0037-tavily-store-and-daily-quota.md), [0038](0038-ai-content-marking.md)

## Context
A fresh guide answer costs three model calls, several searches and up to one web search of the asker. People ask the
same thing in many ways: "What is MCP?", "what is mcp", "what does MCP mean". A cache keyed by the exact text pays for
each spelling again.

Two kinds of difference need different answers:
- Spelling only: case, full-width letters, quote marks, extra spaces, the final "?". Nothing in the answer can depend
  on them. These can share one key.
- Wording: "free tool for chatting with pdf files" and "a free tool to chat with PDFs" are the same need. "a paid
  tool to chat with PDFs" and "rust bindings for python" are not, although their embeddings are close to their
  twins. Sharing an answer here needs more than a text rule, and a wrong share is a wrong answer shown as AI-written.

The answers must survive a restart and be the same on every replica. Redis keeps only rate limits, counters, sessions
and the search cache; AI answers are kept on disk, in PostgreSQL.

## Decision

### 1. Normalised key
`GuideAnswerCache.normalized` changes only what cannot change the answer:
- Unicode compatibility form (NFKC): full-width letters, ligatures, no-break spaces;
- lower case;
- quote marks removed (`" “ ” « » \``, and `'` at the edge of a word); an apostrophe inside a word stays ("what's");
- invisible characters (zero-width spaces) removed, every run of spaces one space, trimmed;
- the punctuation that ends the sentence removed (`. ? ! … , ; :`).

Word order stays: "python bindings for rust" is not "rust bindings for python". "Ollama vs LM Studio" and "LM Studio
vs Ollama" could share an answer, but the comparison's columns follow the order asked, and only the plan knows it is a
comparison; section 3 handles it. Inner punctuation stays ("c++", "node.js", "gpt-4", ".net"), and hyphens stay
("open-source"), because search reads them.

The key is the SHA-256 of normalised question | audience path | config hash ([0017](0017-guide-cost-guard.md)) |
normalised clarification | removed parts of the reading. A clarification and removed parts stay in the key: they are
part of the question ([0032](0032-guide-intents.md) §3, §9). No store sees the question text.

### 2. Store: the shared cache in PostgreSQL
`SharedGuideAnswerStore` keeps each answer in the shared cache `guide-answer` (`SharedCaches`, table `ai_cache`,
[0005](../foundation/0005-redis-disposable-cache.md)), like the other model answers: one primary-key lookup, one upsert,
the `db-cache` policy, the shared cache purge. The stored key is the SHA-256 of the running release
(`H2_BUILD_VERSION`) and the guide's key: a release reads only its own answers, so a deploy that changes how the guide
reads or answers is seen at once. The answer is JSON in a versioned envelope; an entry that does not read is a miss.
It is kept for the answer's time to live counted from its `generatedAt`: 24 h (`H2_GUIDE_CACHE_TTL`), 6 h for a
dated question, never past its web results ([0037](../search/0037-tavily-store-and-daily-quota.md)). A database error
is a miss, never a failed question. Single-flight stays in memory: it coordinates the work of one replica, it does
not cache.

### 3. Reuse for a near-identical question
Off unless `H2_GUIDE_SIMILAR_THRESHOLD` is set. On an exact-key miss, after single-flight and the client quota, the
plan runs as for any fresh question. Then `AskGuide` looks for a stored question read the same way:
- Only a question with no clarification and no removed parts, whose plan succeeded. No model call is added to decide:
  the reading is the plan the fresh answer would use anyway.
- **Reading key** (`ReadingKey`): intent, subjects, constraints, resolved window, reading types wanted, event question,
  and whether the subject is checked for namesakes. Subjects keep the order the user wrote them in, whatever order the
  planner gives, because the order carries the direction; a comparison's subjects are a set. Constraints are a set.
  Names compare without case, hyphens or underscores. The planned search query is left out: two wordings of one need
  are planned with different words.
- **Group** = SHA-256(embedding model | config hash | audience path | reading key). Only questions of the same group
  are compared.
- **Embedding**: the normalised question, embedded as a query by the vector-search model
  ([0012](../search/0012-vector-search-pgvector.md), `nvidia/nemotron-3.5-embed-1b`, 1024 dimensions). An embedding
  failure is a miss.
- **Match**: the closest question of the group whose answer is still stored, if its cosine similarity is at least the
  threshold. Its answer is returned as it is: same `generatedAt`, same AI marking ([0038](0038-ai-content-marking.md)).
- The reused answer is stored under the new question's own key until the original expires, so the next identical
  question is an exact hit. It is not indexed: a chain of near twins would drift from the question that was answered.
- A fresh answer that is cached is indexed in `guide_similar_question (answer_key, group_key, embedding vector(1024),
  expires_at)` (`JdbcSimilarQuestionIndex`), with its answer's expiry. A read skips an expired row; the guide purge
  deletes it the next night ([0034](../operations/0034-data-retention.md)). It is its own table, not the shared cache,
  because the lookup is a distance search, not a key.

The nearest-neighbour search is an exact scan of the group's live rows by cosine distance (`<=>`). A group holds a few
rows at our size, so no approximate index is needed, and the result is exact.

Cost of a reused answer: the plan call (one fast-model call, one unit of the client's daily questions) and one
embedding call. No search, no choose or reason check, and **no web search** of the asker
([0037](../search/0037-tavily-store-and-daily-quota.md)): the answer states the asker's own web searches left, like an exact hit.

### 4. Evaluation, and the threshold
`docs/eval/guide-similar.json`: 55 fixed pairs. 25 should share an answer (paraphrases, word order, spelling, one in
Italian); 30 must not (another subject 8, constraint 6, intent 5, negation 4, direction 4, window 2, kind named 1).
Each side carries the reading a correct plan gives it. Their query embeddings are recorded once
(`./gradlew evalRecord --tests '*GuideSimilarRecorder'`) into `docs/eval/guide-similar-vectors.json`, and
`GuideSimilarEvaluationTest` computes the sweep in every build, so the table below cannot drift from the code.

The recorded vectors come from the open weights of the same model (`nvidia/Nemotron-3.5-Embed-1B-BF16`, query prompt,
first 1024 dimensions, re-normalised, as the API serves them), through the same recorder and `NvidiaEmbedder`.

| Threshold | Reused (of 25) | False reuses (of 30) | Precision | Embedding alone: reused / false |
|---|---|---|---|---|
| 0.80 | 23 | 2 | 0.92 | 24 / 16 |
| 0.90 | 18 | 2 | 0.90 | 19 / 5 |
| 0.93 | 15 | 2 | 0.88 | 16 / 4 |
| 0.95 | 14 | 1 | 0.93 | 15 / 3 |
| **0.96** | **9** | **0** | **1.00** | 10 / 2 |
| 0.97 | 8 | 0 | 1.00 | 9 / 1 |
| 0.98 | 4 | 0 | 1.00 | 4 / 1 |

- With the reading, no must-not pair is reused from 0.96 up. Below it, "ai tools released this year" and "… last
  year" (0.955) share an answer: the guide reads both as the last 365 days ([0032](0032-guide-intents.md) §4), so the
  reading cannot tell them apart. "a model to translate english to italian" and its reverse (0.931) have no subject
  to keep the direction.
- The embedding alone is not enough: "python bindings for rust" is 0.986 from its reverse, closer than most
  paraphrases. The subject order of the reading key is what keeps them apart.
- Missed paraphrases: "postgres" and "postgresql" are different subjects; "when is neurips" and "when does neurips
  take place" are 0.77 apart.

The threshold that meets the rule (no false reuse on the set) is **0.96**, with 36 % of the paraphrases reused. The
default stays off. Before it is set on staging: record the vectors again through the API with the production key, and
run the planner on the direction and negation pairs, to confirm the readings the set assumes.

### 5. Metrics
`helper2.guide.cache{result}`: `exact` (key hit), `similar` (reused), `miss`, once per question when the cache is on.
A reused answer counts as outcome `cached` in `helper2.guide.questions`.

## Consequences
- Spellings of one question share one answer; repeated example questions cost nothing after the first.
- Every replica sees the same answers, and a restart keeps them; a deploy starts a new set.
- With a threshold set, a paraphrase costs one fast-model call and one embedding instead of three model calls and its
  searches.
- A guide question is embedded and its embedding stored next to its answer, for at most the answer's life (24 h) plus
  the night until the purge. Privacy page: "Guide question fingerprints" and the NVIDIA API catalog as a recipient.
  A lawyer must confirm: whether a question embedding is personal data under the nFADP and the GDPR, the legal basis
  for keeping it, and the transfer of the question text to the NVIDIA API catalog in the United States.
- Every read and write of the cache is a database call on the guide's path (a primary-key lookup; the group scan
  reads a few rows), under the `db-cache` policy for the answers.

## Alternatives considered
- **Redis for the answers and a Redis sorted set or Redis 8 vector set for the index** — per-entry time to live for
  free, but model answers are kept on disk and shared from PostgreSQL; a vector set has no time to live per element,
  and its default 8-bit quantisation shifts scores near the threshold.
- **A table of its own for the answers** — a second cache table and a second purge for what the shared cache does.
- **The question embeddings in the shared cache** — a key-value table cannot find the nearest vector.
- **pgvector with an HNSW index on `guide_similar_question`** — approximate, and the filter on group and expiry would
  need an iterative scan ([0012](../search/0012-vector-search-pgvector.md)); an exact scan of a few rows per group is simpler and exact.
- **Reuse before the plan, on the embedding alone** — saves the plan call, but the evaluation shows the embedding
  cannot tell a direction or a negation from a paraphrase.
- **A model call to judge whether two questions are the same** — one more call per miss, and the judge has the
  blind spots of the planner.
- **Reorder the words of "X vs Y" in the key** — the key is built before the plan, so it cannot know it is a
  comparison; "python for rust" would be reordered too.
- **Stemming, stop words or synonyms in the key** — each can change the answer ("free" vs "not free").

## Code
- `backend/src/main/kotlin/ai/helper2/guide/GuideCost.kt` — `GuideAnswerCache` (`key`, `normalized`, `put`),
  `GuideAnswerStore`.
- `backend/src/main/kotlin/ai/helper2/guide/SimilarQuestions.kt` — `ReadingKey`, `SimilarQuestions`, `QuestionEmbedder`,
  `SimilarQuestionIndex`, `SimilarQuestionRetention`.
- `backend/src/main/kotlin/ai/helper2/guide/usecase/AskGuide.kt` — `ask`, `similarAnswer`; `PurgeExpiredData.kt`.
- `backend/src/main/java/ai/helper2/guide/internal/SharedGuideAnswerStore.java`, `JdbcSimilarQuestionIndex.java`,
  `GuideConfiguration.java` (`similarQuestions`), `GuideRetentionSchedule.java`; `platform/PgVectors.java`.
- `backend/src/main/resources/db/migration/V1__schema.sql` — `guide_similar_question`.
- `docs/eval/guide-similar.json`, `guide-similar-vectors.json`; `backend/src/test/kotlin/ai/helper2/eval/GuideSimilarEvaluation.kt`,
  `GuideSimilarEvaluationTest.kt`, `GuideSimilarRecorder.kt`.
- Tests: `GuideAnswerKeyTest`, `SimilarQuestionsTest`, `AskGuideSimilarTest`, `JdbcSimilarQuestionIndexIT`,
  `SharedGuideAnswerStoreTest`, `GuideSimilarConfigurationTest`, `GuideApiIT`.

## Diagrams
[flow](../../diagrams/flow-0041-guide-answer-cache.svg) · [sequence](../../diagrams/sequence-0041-guide-answer-cache.svg)

## Roadmap
- [Similar-question reuse on staging at 0.96, after recording the set through the API](../../roadmap.md#5-ai-quality-and-cost).
