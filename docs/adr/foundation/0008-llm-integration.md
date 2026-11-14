# 0008 — LLM integration

**Depends on:** [0001](0001-stack-and-runtime.md), [0002](0002-modules-modular-monolith-hexagonal-where-the-rules-live.md), [0005](0005-redis-as-a-disposable-cache.md), [0007](0007-configuration-secrets-runtime-settings.md)

## Context
Four features call a large language model (LLM): the artificial intelligence (AI) guide, the Scout's enrichment, search query translation and
the awesome-list classifier. Each call costs provider credits and takes seconds. The public demo is free, so the
spend must have a hard ceiling per day. A provider can be slow, down or overloaded, and the model's answer is
untrusted text: it can be malformed, contain reasoning text, or follow instructions planted in third-party data.
Without a configured model the application must still work.

## Decision
1. **Spring AI 2.0.2 behind ports.** Only `spring-ai-starter-model-openai` is on the classpath. Each feature has its
   own port: `GuideModel`, `EnrichmentModel`, `QueryTranslator`, `RepoClassifier`. The Spring AI adapters
   (`SpringAiGuideModel`, `SpringAiEnrichmentModel`, `SpringAiQueryTranslator`, `SpringAiRepoClassifier`) live in the
   module that owns their port (the translator in `search.internal`) and share the helpers in `ai.helper2.platform.llm`,
   which depends on no feature module.
2. **One provider.** Profile `llm-nebius`: Nebius Token Factory (`https://api.tokenfactory.nebius.com/v1`) serving
   NVIDIA Nemotron models only. `H2_NEBIUS_API_KEY`, `H2_NEBIUS_REASONING_MODEL` (default
   `nvidia/Nemotron-3.5-Super-VL`), `H2_NEBIUS_FAST_MODEL` (default `nvidia/Nemotron-3_5-Lightning`; it
   plans the guide's search, translates queries and classifies awesome lists). Both give JSON schema output, with thinking
   off through `chat_template_kwargs`. The reasoning model is served in `us-north1` (US), the fast model in
   `eu-north1` (Finland). `LlmSettings` holds the fallbacks:
   an empty fast model means the reasoning model does these calls too. NVIDIA-only is checked at startup
   (`NvidiaOnly`): the application does not start when a chat model id sent through the OpenAI-compatible client
   (`spring.ai.openai.chat.model`, `helper2.llm.fast-model`, `helper2.llm.reasoning-model`) or the embedding model id
   ([0024](../search/0024-vector-search.md)) does not start with `nvidia/`; the error names the property.
3. **Prompts on the classpath.** `backend/{libs,apps}/*/src/main/resources/prompts/**.txt`, rendered by `PromptTemplate` with
   `{{name}}` placeholders. Rendering is strict: a missing value or an unused value is an error. Limits the code
   enforces (quote length, allowed values) are passed in, so prompt and validator use the same numbers.
4. **Every call is bounded.** `CallOptions`: temperature 0, explicit model id, a token limit per call (choose and
   enrich 8192 via `H2_NEBIUS_CHOOSE_MAX_TOKENS` / `H2_NEBIUS_ENRICH_MAX_TOKENS`, reason check 4096, translation 400,
   classifier 8192, others the profile's 2048). `spring.ai.openai.chat.timeout` = `H2_LLM_TIMEOUT` (40 s) and
   `max-retries` = `H2_LLM_MAX_RETRIES` (0); the defaults of the provider's software development kit (SDK) are 10 minutes
   and 3 retries. With 0 the SDK does not retry on its own, so every attempt is one of ours and is charged to a daily
   budget. A transient provider error reaches our code at once (`ModelErrors`). A guide call also carries its own
   timeout on the request, never longer than the time its question has left
   ([0032](../guide/0032-guide-cost-guard-and-answer-cache.md)).
5. **Thinking off.** `chat_template_kwargs.enable_thinking=false` on every call. On the plan prompt with Nemotron 3
   Nano, thinking off gives the same plan with 47 completion tokens in 0.5 s, against 526 tokens in 2.5 s with
   thinking on (`CallOptions`).
   The guide can turn it on per step with a token budget (`H2_GUIDE_THINK_PLAN_TOKENS`, `_CHOOSE_TOKENS`,
   `_VERIFY_TOKENS`, default 0 = off; `H2_GUIDE_THINK_BROWSE_TOKENS`, default 2048, for the choose step of a browse).
   `CallOptions.thinking` sends `enable_thinking=true` and `thinking_token_budget`, and asks for the answer's tokens
   plus the budget, so reasoning never takes the room of the answer. Thinking is never on without a budget: on the
   plan prompt Nemotron 3.5 Lightning reasons until the output limit (4096 tokens, 13.8 s, no answer); with a budget
   of 512 it answers in 2.2 s (596 completion tokens), and Nemotron 3.5 Super VL in 5.7 s against 19.6 s without a
   budget. Token Factory ignores `reasoning_budget`, `thinking_budget` and `reasoning_effort` for Lightning.
6. **Daily budget per use, per attempt.** `DailyBudget.tryAcquire()` runs before each provider attempt, retries
   included. Every model adapter makes its call through one `BudgetedChat` (circuit, then budget, then the
   observed call and its token counts); the adapter keeps only the mapping of errors to its own port's failures. The only retry is ours: the guide tries once more when it cannot use an answer, for example malformed
   JavaScript Object Notation (JSON) (`SpringAiGuideModel`). An answer the provider cut at the output limit (finish
   reason `length`, seen by `CutAnswerWatch`, an advisor on each call; a guide call read as a stream gives it the
   finish reason of its chunks) is not tried again: the same prompt with the
   same token limit would be cut again. The one exception is a guide call with a thinking budget: it is tried once
   more with thinking off, when the question has time left for one more call
   ([0032](../guide/0032-guide-cost-guard-and-answer-cache.md); else, and when that call runs out of the time it got,
   the step fails with `cut`). That call
   takes its own budget unit and adds one to `helper2.llm.thinking_fallback{step, reason="cut"}`. Otherwise the guide fails that step with category `cut`; the setup guide answers
   "unreadable". Budgets per day in Coordinated Universal Time (UTC): guide
   `H2_GUIDE_DAILY_MODEL_CALLS` (600), translation `H2_SEARCH_DAILY_TRANSLATIONS` (300), enrichment `H2_SCOUT_DAILY_ENRICHMENT_CALLS` (500; its gap-round share `H2_SCOUT_GAP_DAILY_CALLS`, 400, [0014](../scout/0014-evidence-gated-enrichment.md)), classifier
   `H2_SCOUT_AWESOME_CLASSIFIER_DAILY_CALLS` (300). The counts live in Redis, with a fallback to memory
   ([0005](0005-redis-as-a-disposable-cache.md)): `RedisCallCounter` checks the limit and runs `INCR` and `PEXPIRE` in one Lua
   script, so a counter key always has its expiry and a refused call adds nothing.
7. **A circuit on the provider** ([0006](0006-outbound-calls-and-resilience.md)). Every chat-model call (guide, translation,
   setup guide, enrichment, classifier) runs under the `llm-chat` circuit breaker; the budget unit is taken inside it. Only provider errors count (no
   connection, timeout, 429, 5xx, a rejected call); a malformed answer, a spent budget or a call cut short because its
   question was cancelled (the thread interrupted at the question's deadline, `ModelErrors.isInterrupt`) counts as
   neither success nor failure (`ModelErrors.NotFromProvider`). A cancelled guide question stops: the interrupt is
   kept, and the next step (`GuideSteps.step`) ends the question instead of running more searches. The AI guide's calls run under their own circuit, `llm-chat-guide`, where a
   call slower than `H2_RESILIENCE_LLM_SLOW_CALL` (35 s, just under the 40 s timeout; for a streamed call the time of the whole stream) also counts, so a provider that answers but too late opens it,
   and only the guide degrades. When at least half of the last 10 counted calls failed (or, for the guide, were slow;
   5 calls at least), the circuit opens for 30 s
   (`H2_RESILIENCE_LLM_OPEN_FOR`): each call fails at once with category `circuit_open`, without reaching the provider
   or taking a unit, and the caller's degraded mode starts without waiting for a timeout. Embedding calls have their
   own `llm-embed` circuit, and the background indexer another (`llm-embed-index`, [0006](0006-outbound-calls-and-resilience.md)).
8. **Untrusted input and output.** `UntrustedText` turns `<` `>` in third-party text into `‹` `›` and cuts it, so
   the text cannot close our tags. `ModelAnswers` removes `<think>` blocks (closed or cut off) and takes the outermost
   JSON object. A deterministic validator then decides what is shown (ADR [0031](../guide/0031-guide-pipeline.md),
   [0014](../scout/0014-evidence-gated-enrichment.md)).
9. **Errors and metrics.** `ModelErrors.category` maps a failure to `timeout`, `rate_limited`, `unavailable`,
   `parse_error` or `error`; a call the open circuit refused is `circuit_open`. `ModelCallMetrics` records one
   observation per attempt (span and timer `helper2.llm.call`, tags step, model, prompt, outcome) and the token
   counter `helper2.llm.tokens` (tags step, prompt, type; a count the answer did not report, or 0, adds one call to
   `helper2.llm.tokens_unknown` instead), for every adapter (the setup guide uses steps `tool-guide` and `tool-guide-verify`,
   the classifier step `classify`).
   The prompt tag is the prompt's version: the first 8 hex characters of the SHA-256 of the rendered system prompt
   (`ModelCallMetrics.promptVersion`), so an edited prompt or limit shows as a new series with no number to keep by
   hand. Gauges show the calls left under each daily cap ([0009](0009-observability.md)).
10. **Blocking by default, a stream for the guide.** Every adapter waits for the whole answer
   (`BudgetedChat.text`, `.entity`). The guide can read its calls as a stream instead (`H2_GUIDE_MODEL_STREAM=ON`,
   `BudgetedChat.streamed` and `ChatStream`, [0035](../guide/0035-guide-model-stream.md)): same request, same
   circuit, budget unit and observation around the whole stream, the whole answer checked in the same way. The OkHttp
   dispatcher of the model client runs on virtual threads (`StreamThreads`); only a stream uses it.
11. **Degraded mode.** `spring.ai.model.chat=none` is the default. Without a model, or with the budget spent, each
   feature falls back: the guide shows top search results (`LLM_UNAVAILABLE`), enrichment records `LLM_UNAVAILABLE`
   and retries later, search runs untranslated, the classifier uses keyword rules, the setup guide shows registry and
   README config examples only. An open `llm-chat` circuit gives every feature the same fallback at once.

### Code
- `backend/libs/platform/src/main/java/ai/helper2/platform/llm/` — `LlmSettings`, `LlmConfiguration`, `NvidiaOnly`, `LlmPolicies`,
  `CallOptions`, `BudgetedChat`, `ChatStream`, `StreamThreads`, `CutAnswerWatch`, `DailyBudget`, `CallCounter`, `RedisCallCounter`, `ModelCallMetrics`, `ModelErrors`, `ModelAnswers`,
  `UntrustedText`.
- `backend/libs/platform/src/main/java/ai/helper2/platform/prompt/PromptTemplate.java` — strict prompt rendering.
- `backend/apps/public/src/main/resources/prompts/` — `guide/plan.txt`, `guide/choose.txt`, `guide/compare.txt`, `guide/explain.txt`,
  `guide/verify-reasons.txt`, `setup/tool-guide.txt`, `setup/verify-steps.txt`; `backend/apps/backoffice/src/main/resources/prompts/` —
  `scout/enrich.txt`, `sources/classify-repos.txt`, `sources/read-event.txt`;
  `backend/libs/search/src/main/resources/prompts/search/translate-query.txt`.
- `backend/libs/platform/src/main/resources/helper2-shared.yaml` — profile `llm-nebius`, timeout, retries, budgets.
- `backend/apps/public/src/main/java/ai/helper2/guide/internal/SpringAiGuideModel.java`, `search/internal/SpringAiQueryTranslator.java`,
  `setup/internal/SpringAiToolGuideModel.java`, `scout/internal/SpringAiEnrichmentModel.java`,
  `sources/classifier/SpringAiRepoClassifier.java` — the adapters.

## Alternatives considered
- Provider SDK called directly — ties every adapter to one SDK; Spring AI gives structured output and observations.
- Several providers at once with failover — more setup and secrets for a demo; the degraded mode already covers an outage.
- Prompts in Java strings — wording changes would need code review of escaped text; a strict template catches drift.
- One global budget for all features — a busy Scout or a flood of foreign-language searches could spend the guide's calls.
- Spring AI's `PromptTemplate` — its `{name}` syntax collides with the JSON examples in the prompts.

## Consequences
- The daily spend has a ceiling per feature, and a restart does not reset it while Redis is up.
- A Redis outage can only allow more calls (memory counts from zero), never block the model by mistake.
- Every model call is charged to a daily budget: the SDK makes no hidden retry.
- A short provider error (timeout, 429, 5xx) reaches our code at once: the guide falls back to search results and
  enrichment stays in the backlog for a later run.
- During a provider outage the guide degrades after at most a few timed-out calls per replica, then at once until a
  trial call succeeds.
- A model id of another vendor stops the deploy at startup instead of reaching that vendor.
- Thinking off makes calls faster and repeatable.
- One provider keeps setup and image small. A second provider needs only a dependency and a profile; the ports stay the same.

## Diagrams
[flow](../../diagrams/flow-0008-llm-integration.svg) · [sequence](../../diagrams/sequence-0008-llm-integration.svg)

## Backlog
- Decides: [F1.7](https://github.com/silvioincalza/helper2.ai/issues/26).
- Open issues that change it:
  - [S1.11.1](https://github.com/silvioincalza/helper2.ai/issues/180): a closed `llm` module with a small public API.
