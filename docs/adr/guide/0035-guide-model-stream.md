# 0035 — Guide model stream

**Depends on:** [0006](../foundation/0006-outbound-calls-and-resilience.md), [0008](../foundation/0008-llm-integration.md), [0031](0031-guide-pipeline.md), [0032](0032-guide-cost-guard-and-answer-cache.md), [0033](0033-marking-ai-written-text-research-trail.md).

## Context

A guide question makes up to three model calls (plan, then choose, compare or explain, then the reason check). Each
call is one blocking HTTP request: the adapter waits for the whole answer and sees nothing until it is there. Three
things follow from that.
- The page shows nothing during a model step. With thinking on, a plan call is silent for 30 s.
- A blocking call cannot tell "the model is still thinking" from "the model is writing the answer". When the
  question's time runs out, the call is cut and the step fails, even if a call without thinking would have fitted.
- A cancelled call ends when the HTTP client notices the interrupt or its timeout.

The provider can send the same answer as a stream of chunks (server-sent events). Spring AI gives it as a Reactor
`Flux` (`ChatClient...stream().chatResponse()`).

## Decision

1. **One setting.** `H2_GUIDE_MODEL_STREAM` is `OFF` (default) or `ON` (`GuideProperties.modelStream`, enum
   `ModelStream`). `OFF` is the blocking call of [0008](../foundation/0008-llm-integration.md). `ON` streams every
   guide step: plan, choose, compare, explain and the reason check. Any other value stops the start. The back
   office shows it under Settings, AI guide. There is no setting per step.
2. **One place.** `SpringAiGuideModel.call` is the only code that asks the model, in both modes. With `ON` it asks
   through `ChatClient...stream().chatResponse()` with the same model, options, system text and user text. Because
   a stream gets no structured output from Spring AI, the adapter does what the blocking advisor does: with the
   native schema it sets `outputSchema(converter.getJsonSchema())` on the options, else it appends
   `converter.getFormat()` to the user text after a line break. The request body is the blocking body plus
   `stream: true` and `stream_options.include_usage`. A test compares the two bodies.
3. **No unchecked AI text reaches the browser.** `ChatStream.collect` (platform `llm`) subscribes, takes the chunks
   on the question's virtual thread and joins the answer text. Only the whole text leaves it. The adapter converts
   it with the same `BeanOutputConverter` (`ModelAnswers.jsonObject` first), and `GuideValidator` and the reason
   check decide what is shown, as in [0031](0031-guide-pipeline.md). `AskGuide` stays blocking, top-down code. The
   controllers stay Spring MVC ([0031](0031-guide-pipeline.md), Streaming).
4. **One call is still one unit.** `BudgetedChat.streamed` runs the same order as a blocking call: circuit
   (`llm-chat-guide`), then one budget unit, then one observation (`helper2.llm.call`). They wrap the **whole
   subscription**, from subscribe to the last chunk or to the dispose. So the slow-call rule of the circuit
   measures the whole stream, not the 0.3 s to the response headers. The daily budget counts calls, not tokens: it
   is the same in both modes.
5. **Stop at once.** A stream is disposed, and its HTTP exchange ends, in three cases. The step fails with the
   same categories as a blocking call.
   - The call's window is over (`CallTimeLimit`, [0032](0032-guide-cost-guard-and-answer-cache.md)): category
     `deadline` when the question's deadline shortened the window (not a provider failure), `timeout` when the
     call used its whole model timeout (a provider failure for the circuit). The same window is still set as the
     request timeout, because a dispose before the response headers does not abort the request.
   - The question is cancelled: `GuideLimits` interrupts the worker at the question's deadline. Category
     `cancelled`, the interrupt is kept, not a provider failure.
   - Thinking took the time of the answer (point 6).

   With `ON` and a thinking budget above 0 for plan, choose or verify, a question whose askers have all closed the
   connection is interrupted the same way, and has no answer. With `OFF`, or with no step that thinks, it runs on
   and its answer is cached ([0032](0032-guide-cost-guard-and-answer-cache.md)).
6. **Thinking is capped by time** (`CallTimeLimit.forThinking`). The token budget of
   [0008](../foundation/0008-llm-integration.md) bounds what thinking may write, not how long it takes (a plan call
   with 2048 thinking tokens thinks for 29 s). The rule, for a streamed call:
   - It applies to the first attempt of a step that has a thinking budget, in a question with a deadline.
   - The answer text must start while the question still has `deadline-margin` + `model-call-minimum` left (2 s +
     10 s). That is the time one more call needs.
   - At that moment, with reasoning chunks only and no answer text, the stream is disposed (`ThinkingTimeout`: not
     a provider failure, outcome `timeout` on the observation). The step is asked once more with thinking off: the
     same second call as for an answer cut while thinking, with its own budget unit and
     `helper2.llm.thinking_fallback{step, reason="time"}` (`reason="cut"` for a cut answer).
   - That second call gets the time left minus the margin. The minimum is not checked again: the time was kept
     for this call.
   - The rule does not apply when the call starts with no more than margin + minimum left (no time can be kept),
     or when the model timeout ends the call first. Then the call runs as without the rule.

   With `OFF` there is no such rule: a blocking call cannot see whether the answer has started.
7. **Cut, malformed, fallback: unchanged.** The finish reason is read from the chunks (the last one that has it);
   `length` or `max_tokens` is a cut answer. A cut answer is not asked again, except once with thinking off when
   it was thinking. A malformed answer is asked once more. A failure of the stream after the first chunk and an
   SSE `error` event after a 200 are provider errors (`ModelErrors`: `SseException` is an `OpenAIException`) and
   count for the circuit.
8. **Progress to the browser.** Two new events of `POST /api/v1/guide/stream`: `reasoning` and `writing`, each
   with the step only (`GuideStreamModelStep`: `plan`, `choose`, `compare`, `explain`, `verify`). `reasoning` is
   sent when the first reasoning chunk of a step arrives, `writing` when its first answer text arrives; each at
   most once per step. They carry no model text and no count. The adapter reports them through `ModelActivity`, a
   scoped value like `QuestionDeadline`, that `AskGuide` binds to the question's `GuideProgress`. An asker who
   follows a running question gets none, like the other step events. With `OFF` neither event is sent. The page
   shows the step in its progress line ("Thinking about the plan", "Writing the picks").
   **EU AI Act Art. 50:** the two events say what the system is doing. They hold no AI-written text, so they
   carry no `aiContent` marker and no label; `GuideAiFieldsTest` classes `step` as not AI-written.
9. **Usage.** Per call, the token counts are the highest values any chunk reported. A count that is missing or 0
   is not a real 0: `helper2.llm.tokens` gets nothing, and `helper2.llm.tokens_unknown{step,prompt,type}` counts
   the call. The rule is the same for a blocking call. So the reasoning model's streamed calls show their
   completion tokens as unknown, never as 0.
10. **Threads.** The adapter adds no platform thread: it waits on the question's virtual thread. The OkHttp
    dispatcher of the Spring AI client runs on virtual threads (`StreamThreads`, an
    `OpenAiHttpClientBuilderCustomizer` bean), instead of its unbounded pool of platform threads. Spring AI gives
    no way to replace the SDK's stream handler pool: one `openai-stream-handler-thread` is held per running
    stream. Their number is bounded by the questions running at once (the `guide` bulkhead,
    [0032](0032-guide-cost-guard-and-answer-cache.md)): a question makes one model call at a time. The hand-over
    runs on Reactor's shared `boundedElastic` pool (at most 10 threads per CPU).
11. **Not part of the configuration hash.** The hash ([0032](0032-guide-cost-guard-and-answer-cache.md)) names
    what can change an answer. The mode does not: the provider gets the same model, prompt, schema, token limits
    and temperature 0, and the same text goes through the same converter and the same checks. The thinking time
    rule can only replace "no answer in time" (which is never cached) with the answer of the call without
    thinking, and that call's answer is already a value of the same hash (a cut thinking call gives it too). So a
    change of mode keeps the answer cache and the similar-question index.
12. **Only the guide.** The setup guide, query translation, enrichment and the classifier stay blocking: thinking
    off, calls under a second or in the background, no page waiting on a step. `BudgetedChat.text` and `.entity`
    are unchanged.

### Code

- `backend/libs/platform/src/main/java/ai/helper2/platform/llm/ChatStream.java` — collects a stream on the calling
  thread: text, finish reason, usage, first reasoning and first text, the window and the thinking limit.
- `backend/libs/platform/src/main/java/ai/helper2/platform/llm/BudgetedChat.java` (`streamed`),
  `ModelCallMetrics.java` (`tokens`, `tokens_unknown`, `thinkingFallback`), `CutAnswerWatch.java` (`isCut`),
  `StreamThreads.java`, `LlmConfiguration.java`.
- `backend/apps/public/src/main/java/ai/helper2/guide/internal/SpringAiGuideModel.java` (`call`, `streamWithin`),
  `GuideConfiguration.java`; `guide/config/GuideProperties.java`, `ModelStream.java`; `guide/CallTimeLimit.java`
  (`forThinking`, `forTheCallAfterThinking`); `guide/ModelActivity.java`; `guide/Guide.kt` (`ModelStep`,
  `GuideProgress.reasoning`, `.writing`); `guide/usecase/AskGuide.kt`; `guide/web/GuideStreamController.java`.
- `api/public.yaml` — `GuideStreamEvent`, `GuideStreamModelStep`.
- `frontend/src/features/guide/GuidePicks.tsx`, `frontend/src/api/types.ts`, `frontend/src/i18n/`.
- Tests: `ChatStreamTest`, `BudgetedChatTest`, `ModelCallMetricsTest`; `ThinkingOnTheWireTest`,
  `CallTimeOnTheWireTest` and `ThinkingFallbackTest` run in both modes; `StreamOnTheWireTest`, `StreamThreadsTest`
  and `GuideModelStreamApiIT` for the stream; `FakeProvider` answers as JSON or as SSE chunks;
  `GuideModelStreamProbeLiveTest` (opt-in, real provider).

## Alternatives considered

- **WebFlux end to end** (reactive controllers, a reactive `AskGuide`). The steps of a question are sequential and
  full of early returns; as a reactive chain they are harder to read, test and debug for one person, and JDBC,
  the search adapters and Resilience4j's blocking decorators would all need a second form. Virtual threads already
  give cheap waiting. Rejected.
- **Stream only when thinking is on.** Fewer streamed calls, but two code paths chosen per call, and the wire
  tests and the staging comparison double. The progress events would appear for some steps only. Rejected; one
  setting for every step is simpler to reason about and to roll back.
- **Keep blocking.** No new code. The page stays silent for up to 30 s, and a thinking call that runs long fails
  the step. Kept as `OFF`, the default, until the staging comparison is done.
- **Show the partial answer text while it arrives.** Faster to read, but it is unchecked AI text on the page: it
  breaks the evidence gate of [0031](0031-guide-pipeline.md) and needs Art. 50 marking of text that may be
  withdrawn a second later. Rejected.
- **`chatModel.stream(prompt)` without `ChatClient`.** One thread hand-over less (`boundedElastic`), but the
  prompt is built twice in two ways and the chat client observation is lost on streams. Rejected for now.
- **Our own `OpenAIClientAsync` with a virtual-thread stream handler.** It would free the last platform thread per
  stream, but it takes the place of Spring AI's auto-configured client (key, timeout, retries, observation, metrics) with a
  copy to keep in step. Rejected; the bulkhead already bounds the threads.

## Consequences

- With `ON` the page says what a silent model step is doing, from the first chunk (about 0.3 s).
- A step that thinks too long is answered without thinking instead of failing at the deadline.
- A stopped call ends its HTTP exchange at once, and the provider stops generating for it.
- Streaming does not make an answer faster. With thinking off (the default for plan, choose and verify) the gain
  is the progress line only.
- A change of the setting needs a restart and keeps the cache. `OFF` is the rollback.
- Each running stream holds one SDK platform thread. With the default 8 guide slots that is at most 8.
- The reasoning model's completion tokens are unknown on a stream: the tokens panel undercounts with `ON`.
  `helper2.llm.tokens_unknown` says by how many calls.

### Measurement

Done on 11 Nov 2026 06:20 UTC on the real provider (Nebius Token Factory), 13 calls, with
`GuideModelStreamProbeLiveTest`. Times in ms.

| step | thinking off, blocking / stream total | thinking on (2048 tokens), blocking / stream total | first reasoning chunk | first content chunk (thinking on) |
|---|---|---|---|---|
| plan (Nemotron-3_5-Lightning) | 1451 / 2212 (the first stream paid the client start) | 31416 / 30373 | 266 | 29284 |
| choose (Nemotron-3.5-Super-VL) | 810 / 751 | 9656 / 8926 | 325 | 8695 |
| verify (Nemotron-3.5-Super-VL) | 712 / 396 | 3791 / 2463 | 309 | 2387 |

What the calls showed:
- The response headers arrive after about 0.3 s. **A stream is not faster in total.**
- `response_format` with a JSON schema works with `stream=true`, and the assembled text parses.
- The finish reason arrives on the second-to-last chunk.
- Usage: the fast model reports prompt and completion tokens on the last chunk. The reasoning model reports only
  prompt tokens (completion 0), on the first chunk.
- Reasoning arrives in the message metadata `reasoningContent`, as the text so far. The answer text arrives as
  pieces.
- A stream disposed after 2 s ended the HTTP/2 exchange in 1 ms (the body is closed; the OkHttp call is not
  "cancelled").

What the source of Spring AI 2.0.2 and openai-java 4.80 says (each point read in the source):
1. `stream()` applies no structured output: there is no `entity()`, and neither the schema nor the format text is
   added (`ChatModelStreamAdvisor` against `ChatModelCallAdvisor.augmentWithFormatInstructions`).
2. A `CallAdvisor` is not part of a stream: `CutAnswerWatch` sees nothing.
3. `stream_options.include_usage=true` is sent by default (`OpenAiChatModel.createRequest`). Setting stream options
   by hand also sends `include_obfuscation`.
4. `options.timeout(d)` becomes OkHttp's call timeout and read timeout (`SpringAiOpenAiHttpClient`), for a stream
   too.
5. The SDK retries only before it hands the response over. A failure after the first chunk is an error of the
   `Flux`. The provider can send an `error` event after a 200: the SDK throws `SseException` (`SseHandler`).
6. `dispose()` closes the response body. Before the response headers it does not abort the request.
7. A stream leaves the caller's thread: the request runs on an OkHttp dispatcher thread, the chunks are read on one
   `openai-stream-handler-thread` per running stream (a cached, unbounded pool of platform threads, built inside
   `ClientOptions`), and `ChatModelStreamAdvisor` hands them over on `Schedulers.boundedElastic()`. Nothing pushes
   back to the socket.
8. The chat model observation stops on cancel with no usage.

### Privacy and terms

No new data flow. The provider gets the same request (plus `stream: true`). The two new events hold a step name;
the page keeps them in memory with the other steps of the running question and stores nothing new (the
recent-answers cache keeps answers only, [0039](../frontend/0039-frontend-one-box-state-in-the-url.md)). No new log line with user
text. The Privacy and Terms pages do not change.

### STRIDE

What changes against [0031](0031-guide-pipeline.md) and [0008](../foundation/0008-llm-integration.md):

| Threat | Risk | Control |
|---|---|---|
| Spoofing | A chunk pretends to be from the provider | Same TLS connection and key as the blocking call; no new endpoint. |
| Tampering / prompt injection (OWASP LLM01, LLM05) | Partial model text shown before it is checked | No chunk text leaves `ChatStream.collect`; only the whole text is converted and checked. `reasoning` and `writing` carry a step from a fixed enum. Reasoning text is never read into the answer and never logged. |
| Repudiation | A stopped stream leaves no trace | One `helper2.llm.call` observation per subscription with its outcome (`ok`, `timeout`, `unavailable`, …); `thinking_fallback{reason}` says why the second call ran. |
| Information disclosure | Reasoning text leaks to the page or the logs | Only "a reasoning chunk arrived" is kept (a flag). The debug log line prints the converted answer, as before. |
| Denial of service | Many open streams hold threads and sockets; a provider that drips chunks keeps a call open | One stream per running question, bounded by the `guide` bulkhead and the per-client limit; the call window and the question deadline dispose it; the circuit times the whole subscription; chunks are joined up to the token limit of the call. |
| Elevation of privilege | — | No new permission, no tool calling. |

### AI architecture check

| # | Layer | State |
|---|---|---|
| 1 | Model | Covered: same models and options in both modes; NVIDIA only. |
| 2 | Agent Framework | Deferred, safe: no agent loop. |
| 3 | Orchestration | Covered: the stream is consumed inside the blocking step; the call window, the question deadline and the thinking time rule dispose it; one retry and one call without thinking as before. |
| 4 | Tool & API | Covered: no tool calling; same endpoint with `stream: true`. |
| 5 | Memory | Covered: nothing kept between chunks beyond the call. |
| 6 | Knowledge & RAG | Covered: unchanged. |
| 7 | Data | Covered: nothing stored; no schema change. |
| 8 | Browser & Computer Use | Covered: none. |
| 9 | Guardrails | Covered: the whole answer goes through the converter, the evidence gate and the reason check; no partial text is shown. |
| 10 | Observability | Covered: `helper2.llm.call` around the whole stream, `helper2.llm.tokens_unknown`, `thinking_fallback{reason}`; Spring AI's chat model observation still runs. Gap, deferred: no timer for the first chunk. |
| 11 | Evaluation | Covered: the guide eval runs against either mode; the wire tests run in both. A staging run `ON` against `OFF` is open ([#279](https://github.com/silvioincalza/helper2.ai/issues/279)). |
| 12 | Deployment | Covered: one variable, Helm value `app.guideModelStream`, default `OFF`; rollback is `OFF`. |
| 13 | Security & Identity | Covered: same key, no new secret. |
| 14 | Human-in-the-Loop | Covered: unchanged; the progress line tells the person what the system does. |
| 15 | Cost & Reliability | Covered: one budget unit per call in both modes; a stopped stream stops the provider's work; the circuit sees mid-stream failures. |

### Known limits

- Guide model stream (`H2_GUIDE_MODEL_STREAM=ON`): a stream is not faster in total; each running stream holds one
  SDK platform thread (`openai-stream-handler-thread`, no way to replace the pool through Spring AI) and passes
  through Reactor's shared `boundedElastic` pool; a dispose before the response headers does not abort the request
  (the request timeout does); the reasoning model reports no completion tokens on a stream, so its tokens are
  counted as unknown; a provider that writes its reasoning inside the answer text (`<think>`) is seen as writing,
  and the thinking time rule does not stop it; an asker who follows a running question sees no `reasoning` or
  `writing` event; there is no timer for the first chunk; the mode is not in the configuration hash, so an eval of
  the two modes needs `H2_GUIDE_CACHE_TTL=PT1S`.

## Diagrams

[flow](../../diagrams/flow-0035-guide-model-stream.svg) · [sequence](../../diagrams/sequence-0035-guide-model-stream.svg)

## Backlog

- Features: [F4.5](https://github.com/silvioincalza/helper2.ai/issues/68) Model calls as a stream (setting).
- Open issues that change this decision:
  - [#279](https://github.com/silvioincalza/helper2.ai/issues/279) S4.5.2 `H2_GUIDE_MODEL_STREAM=ON` and `OFF` compared on staging
