# 0014 — LLM integration: Spring AI behind ports, one OpenAI-compatible provider, prompts on the classpath, thinking off, timeouts, budget per attempt, degraded mode

**Status.** Accepted (2026-10-27). **Builds on:** [0001](0001-stack-and-runtime.md), [0002](0002-modular-monolith-hexagonal.md), [0005](0005-redis-disposable-cache.md), [0007](0007-configuration-and-secrets.md)

## Context
Four features call a large language model (LLM): the artificial intelligence (AI) guide, the Scout's enrichment, search query translation and
the awesome-list classifier. Each call costs provider credits and takes seconds. The public demo is free, so the
spend must have a hard ceiling per day. A provider can be slow, down or overloaded, and the model's answer is
untrusted text: it can be malformed, contain reasoning text, or follow instructions planted in third-party data.
Without a configured model the application must still work.

## Decision
1. **Spring AI 2.0.2 behind ports.** Only `spring-ai-starter-model-openai` is on the classpath. Each feature has its
   own port: `GuideModel`, `EnrichmentModel`, `QueryTranslator`, `RepoClassifier`. The Spring AI adapters
   (`SpringAiGuideModel`, `SpringAiEnrichmentModel`, `SpringAiQueryTranslator`, `SpringAiRepoClassifier`) share the
   helpers in `ai.helper2.platform.llm`.
2. **One provider.** Profile `llm-nebius`: Nebius Token Factory (`https://api.tokenfactory.nebius.com/v1`) serving
   NVIDIA Nemotron models only. `H2_NEBIUS_API_KEY`, `H2_NEBIUS_REASONING_MODEL` (default
   `nvidia/nemotron-3-super-120b-a12b`), `H2_NEBIUS_FAST_MODEL` (default `nvidia/NVIDIA-Nemotron-3-Nano-30B-A3B`; it
   plans the guide's search, translates queries and classifies awesome lists). `LlmSettings` holds the fallbacks:
   an empty fast model means the reasoning model does these calls too.
3. **Prompts on the classpath.** `backend/src/main/resources/prompts/**.txt`, rendered by `PromptTemplate` with
   `{{name}}` placeholders. Rendering is strict: a missing value or an unused value is an error. Limits the code
   enforces (quote length, allowed values) are passed in, so prompt and validator use the same numbers.
4. **Every call is bounded.** `CallOptions`: temperature 0, explicit model id, a token limit per call (choose and
   enrich 8192 via `H2_NEBIUS_CHOOSE_MAX_TOKENS` / `H2_NEBIUS_ENRICH_MAX_TOKENS`, reason check 4096, translation 400,
   classifier 8192, others the profile's 2048). `spring.ai.openai.chat.timeout` = `H2_LLM_TIMEOUT` (40 s) and
   `max-retries` = `H2_LLM_MAX_RETRIES` (0); the defaults of the provider's software development kit (SDK) are 10 minutes
   and 3 retries. With 0 the SDK does not retry on its own, so every attempt is one of ours and is charged to a daily
   budget. A transient provider error reaches our code at once (`ModelErrors`).
5. **Thinking off.** `chat_template_kwargs.enable_thinking=false` on every call. `CallOptions` records the measurement
   of 27 Oct 2026 on the plan prompt with Nemotron 3 Nano: 526 vs 47 completion tokens, 2.5 s vs 0.5 s, same plan.
   The guide can turn it on per step (`H2_GUIDE_THINK_PLAN|CHOOSE|VERIFY`, default false).
6. **Daily budget per use, per attempt.** `DailyBudget.tryAcquire()` runs before each provider attempt, retries
   included. The only retry is ours: the guide tries once more when it cannot use an answer, for example malformed
   JavaScript Object Notation (JSON) (`SpringAiGuideModel`). Budgets per day in Coordinated Universal Time (UTC): guide
   `H2_GUIDE_DAILY_MODEL_CALLS` (600), translation `H2_SEARCH_DAILY_TRANSLATIONS` (300), enrichment `H2_SCOUT_DAILY_ENRICHMENT_CALLS` (300), classifier
   `H2_SCOUT_AWESOME_CLASSIFIER_DAILY_CALLS` (300). The counts live in Redis, with a fallback to memory
   ([0005](0005-redis-disposable-cache.md)): `RedisCallCounter` runs `INCR` and `PEXPIRE` in one Lua script, so a
   counter key always has its expiry.
7. **Untrusted input and output.** `UntrustedText` turns `<` `>` in third-party text into `‹` `›` and cuts it, so
   the text cannot close our tags. `ModelAnswers` removes `<think>` blocks (closed or cut off) and takes the outermost
   JSON object. A deterministic validator then decides what is shown (ADR [0015](0015-ai-guide-evidence-gate.md),
   [0019](0019-evidence-gated-enrichment.md)).
8. **Errors and metrics.** `ModelErrors.category` maps a failure to `timeout`, `rate_limited`, `unavailable`,
   `parse_error` or `error`. `ModelCallMetrics` records one observation per attempt (`helper2.guide.model.call`, tags
   step, model, outcome) and the token counter `helper2.guide.tokens`, for every adapter (the classifier uses
   step `classify`). Gauges show the calls left under each daily cap ([0026](0026-observability.md)).
9. **Degraded mode.** `spring.ai.model.chat=none` is the default. Without a model, or with the budget spent, each
   feature falls back: the guide shows top search results (`LLM_UNAVAILABLE`), enrichment records `LLM_UNAVAILABLE`
   and retries later, search runs untranslated, the classifier uses keyword rules.

## Consequences
- The daily spend has a ceiling per feature, and a restart does not reset it while Redis is up.
- A Redis outage can only allow more calls (memory counts from zero), never block the model by mistake.
- Every model call is charged to a daily budget: the SDK makes no hidden retry by default. Setting
  `H2_LLM_MAX_RETRIES` above 0 would add attempts that no budget counts.
- A short provider error (timeout, 429, 5xx) is not retried at once: the guide falls back to search results and
  enrichment stays in the backlog for a later run.
- Thinking off makes calls faster and repeatable, but the chooser returns fewer picks (see [0030](0030-guide-evaluation.md)).
- One provider keeps setup and image small. A second provider needs a dependency and a profile; the ports do not change.

## Alternatives considered
- Provider SDK called directly — ties every adapter to one SDK; Spring AI gives structured output and observations.
- Several providers at once with failover — more setup and secrets for a demo; the degraded mode already covers an outage.
- Prompts in Java strings — wording changes would need code review of escaped text; a strict template catches drift.
- One global budget for all features — a busy Scout or a flood of foreign-language searches could spend the guide's calls.
- Spring AI's `PromptTemplate` — its `{name}` syntax collides with the JSON examples in the prompts.

## Code
- `backend/src/main/java/ai/helper2/platform/llm/` — `LlmSettings`, `LlmConfiguration`, `CallOptions`, `DailyBudget`,
  `CallCounter`, `RedisCallCounter`, `ModelCallMetrics`, `ModelErrors`, `ModelAnswers`, `UntrustedText`,
  `SpringAiQueryTranslator`.
- `backend/src/main/java/ai/helper2/platform/prompt/PromptTemplate.java` — strict prompt rendering.
- `backend/src/main/resources/prompts/` — `guide/plan.txt`, `guide/choose.txt`, `guide/verify-reasons.txt`,
  `guide/translate-query.txt`, `scout/enrich-v3.txt`, `sources/classify-repos.txt`.
- `backend/src/main/resources/application.yaml` — profile `llm-nebius`, timeout, retries, budgets.
- `backend/src/main/java/ai/helper2/guide/internal/SpringAiGuideModel.java`, `scout/internal/SpringAiEnrichmentModel.java`,
  `sources/SpringAiRepoClassifier.java` — the adapters.

## Diagrams
[flow](../diagrams/flow-0014-llm-integration.svg) · [sequence](../diagrams/sequence-0014-llm-integration.svg)

## Roadmap
- [Resilience4j for outbound calls](../roadmap.md#5-ai-quality-and-cost)
- [A closed `llm` module instead of the open `platform` module](../roadmap.md#8-architecture)
- [Alert when a daily budget runs low](../roadmap.md#3-operations)
