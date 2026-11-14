package ai.helper2.guide.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.eval.EvalFiles;
import ai.helper2.platform.llm.CallOptions;
import ai.helper2.platform.llm.CutAnswerWatch;
import ai.helper2.platform.llm.ModelAnswers;
import ai.helper2.platform.llm.ModelErrors;
import com.openai.models.completions.CompletionUsage;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import okhttp3.Call;
import okhttp3.Interceptor;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.ForwardingSource;
import okio.Okio;
import okio.Source;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.client.AdvisorParams;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Hooks;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Tag("live")
@EnabledIfEnvironmentVariable(named = "H2_NEBIUS_API_KEY", matches = ".+")
class GuideModelStreamProbeLiveTest {

  static final String BASE_URL = "https://api.tokenfactory.nebius.com/v1";
  static final String REPORT = "guide-stream-probe.md";
  static final Duration CALL_TIMEOUT = Duration.ofSeconds(60);
  static final Duration CANCEL_AFTER = Duration.ofSeconds(2);
  static final Duration LATE_CHUNKS_WINDOW = Duration.ofMillis(500);
  static final int MAX_MODEL_CALLS = 13;
  static final int THINKING_TOKENS_WHEN_ON = 2048;
  static final int CONTENT_PREVIEW_CHARS = 60;
  static final String REASONING_METADATA = "reasoningContent";
  static final String INLINE_REASONING = "<think>";
  static final String NEED = "an open source MCP server to query a PostgreSQL database";
  static final String NOT_SEEN = "-";

  static final JsonMapper JSON = JsonMapper.builder().build();

  final String fastModel = env("H2_NEBIUS_FAST_MODEL", "nvidia/Nemotron-3_5-Lightning");
  final String reasoningModel = env("H2_NEBIUS_REASONING_MODEL", "nvidia/Nemotron-3.5-Super-VL");
  final boolean nativeSchema = Boolean.parseBoolean(env("H2_NEBIUS_NATIVE_SCHEMA", "true"));
  final int chooseMaxTokens =
      Integer.parseInt(
          env(
              "H2_NEBIUS_CHOOSE_MAX_TOKENS",
              String.valueOf(SpringAiGuideModel.DEFAULT_CHOOSE_MAX_TOKENS)));

  final ExchangeWatch exchanges = new ExchangeWatch();
  final AtomicInteger modelCalls = new AtomicInteger();
  final ChatClient chat = ChatClient.builder(chatModel()).build();

  @Test
  void theProviderStreamIsMeasuredNextToTheBlockingCall() throws Exception {
    List<Case> cases = cases();
    Case planThinking = cases.get(1);
    Case chooseThinking = cases.get(3);

    Map<Case, StreamRun> streamed = new LinkedHashMap<>();
    cases.forEach(c -> streamed.put(c, streamOnce(c)));
    boolean springAiShowsReasoning =
        streamed.entrySet().stream()
            .anyMatch(run -> run.getKey().thinks() && run.getValue().showsReasoning());

    Map<Case, BlockingRun> blocking = new LinkedHashMap<>();
    for (Case c : cases) {
      boolean givesItsCallToTheRawRequest = !springAiShowsReasoning && c == planThinking;
      blocking.put(c, givesItsCallToTheRawRequest ? BlockingRun.notRun() : callOnce(c));
    }
    RawStream raw = springAiShowsReasoning ? null : rawStream(chooseThinking);
    Cancel cancel = cancelAfterTwoSeconds(chooseThinking);

    String report = report(cases, blocking, streamed, raw, cancel);
    File file = new File(EvalFiles.INSTANCE.getData(), REPORT);
    file.getParentFile().mkdirs();
    Files.writeString(file.toPath(), report, StandardCharsets.UTF_8);
    System.out.println(report);

    assertThat(modelCalls.get()).isLessThanOrEqualTo(MAX_MODEL_CALLS);
    assertThat(file).exists();
  }

  record Case(
      String name,
      String model,
      String system,
      String user,
      Class<?> answer,
      boolean nativeSchema,
      int answerTokens,
      int thinkingTokens) {

    boolean thinks() {
      return thinkingTokens > 0;
    }
  }

  private List<Case> cases() {
    List<Case> cases = new ArrayList<>();
    for (boolean think : List.of(false, true)) {
      cases.add(
          new Case(
              "plan, thinking " + onOff(think),
              fastModel,
              SpringAiGuideModel.planSystem(),
              "Audience: any\n" + SpringAiGuideModel.need(NEED),
              SpringAiGuideModel.Plan.class,
              false,
              SpringAiGuideModel.PLAN_MAX_TOKENS,
              think ? thinkingTokens("H2_GUIDE_THINK_PLAN_TOKENS") : 0));
    }
    for (boolean think : List.of(false, true)) {
      cases.add(
          new Case(
              "choose, thinking " + onOff(think),
              reasoningModel,
              SpringAiGuideModel.chooseSystem(),
              chooseUser(),
              SpringAiGuideModel.Choice.class,
              nativeSchema,
              chooseMaxTokens,
              think ? thinkingTokens("H2_GUIDE_THINK_BROWSE_TOKENS") : 0));
    }
    for (boolean think : List.of(false, true)) {
      cases.add(
          new Case(
              "verify, thinking " + onOff(think),
              reasoningModel,
              SpringAiGuideModel.verifySystem(),
              verifyUser(),
              SpringAiGuideModel.Verdicts.class,
              nativeSchema,
              SpringAiGuideModel.VERIFY_MAX_TOKENS,
              think ? thinkingTokens("H2_GUIDE_THINK_VERIFY_TOKENS") : 0));
    }
    return cases;
  }

  record FakeCandidate(String key, String type, String license, String text) {}

  static final List<FakeCandidate> CANDIDATES =
      List.of(
          new FakeCandidate(
              "c1",
              "MCP_SERVER",
              "MIT",
              "Postgres MCP server\nAn MCP server that lets AI agents read the schema and run"
                  + " read-only SQL queries on a PostgreSQL database."),
          new FakeCandidate(
              "c2",
              "LIBRARY",
              "Apache-2.0",
              "pgvector\nOpen-source vector similarity search for Postgres. Store embeddings next"
                  + " to the rest of your data."),
          new FakeCandidate(
              "c3",
              "MCP_SERVER",
              "MIT",
              "SQLite MCP server\nAn MCP server to query and change a local SQLite database file"
                  + " from an AI assistant."));

  private static String chooseUser() {
    String list =
        CANDIDATES.stream()
            .map(c ->
                "<candidate key=\""
                    + c.key()
                    + "\" type=\""
                    + c.type()
                    + "\" license=\""
                    + c.license()
                    + "\">\n"
                    + SpringAiGuideModel.untrusted(c.text())
                    + "\n</candidate>")
            .collect(Collectors.joining("\n"));
    return SpringAiGuideModel.need(NEED)
        + "\n\n<constraints>\nopen source\n</constraints>\n\nCANDIDATES:\n"
        + list;
  }

  private static String verifyUser() {
    FakeCandidate picked = CANDIDATES.getFirst();
    String quote = "run read-only SQL queries on a PostgreSQL database";
    return SpringAiGuideModel.need(NEED)
        + "\n\nPICKS:\n<pick key=\""
        + picked.key()
        + "\">\n<reason>"
        + SpringAiGuideModel.untrusted(
            "It is an MCP server that runs read-only SQL queries on PostgreSQL.")
        + "</reason>\n<evidence>\nText: "
        + SpringAiGuideModel.untrusted(picked.text())
        + "\nQuote: "
        + SpringAiGuideModel.untrusted(quote)
        + "\n</evidence>\n</pick>";
  }

  private OpenAiChatModel chatModel() {
    return OpenAiChatModel.builder()
        .options(
            OpenAiChatOptions.builder()
                .baseUrl(BASE_URL)
                .apiKey(System.getenv("H2_NEBIUS_API_KEY"))
                .model(reasoningModel)
                .timeout(CALL_TIMEOUT)
                .maxRetries(0)
                .build())
        .httpClientBuilderCustomizer(http -> http.interceptor(exchanges))
        .build();
  }

  private static OpenAiChatOptions.Builder options(Case c) {
    return CallOptions.thinking(c.model(), c.answerTokens(), c.thinkingTokens())
        .timeout(CALL_TIMEOUT);
  }

  private static <T> BeanOutputConverter<T> converter(Class<T> type) {
    return new BeanOutputConverter<>(type, null, text -> ModelAnswers.jsonObject(text).orElse(""));
  }

  private void countAModelCall() {
    if (modelCalls.incrementAndGet() > MAX_MODEL_CALLS) {
      throw new IllegalStateException("more than " + MAX_MODEL_CALLS + " model calls in one run");
    }
  }

  record Tokens(long prompt, long completion, long total, Long reasoning) {

    static Tokens of(Usage usage) {
      if (usage == null || usage.getTotalTokens() == null || usage.getTotalTokens() <= 0) {
        return null;
      }
      Long reasoning =
          usage.getNativeUsage() instanceof CompletionUsage wire
              ? wire.completionTokensDetails().flatMap(d -> d.reasoningTokens()).orElse(null)
              : null;
      return new Tokens(
          usage.getPromptTokens(), usage.getCompletionTokens(), usage.getTotalTokens(), reasoning);
    }

    String cell() {
      return prompt
          + "/"
          + completion
          + "/"
          + total
          + (reasoning == null ? "" : " (reasoning " + reasoning + ")");
    }
  }

  record BlockingRun(
      boolean ran,
      long totalMs,
      String finishReason,
      boolean cut,
      Tokens tokens,
      String reasoning,
      String parsed,
      String content,
      String error) {

    static BlockingRun notRun() {
      return new BlockingRun(false, 0, NOT_SEEN, false, null, NOT_SEEN, NOT_SEEN, "", "");
    }
  }

  private BlockingRun callOnce(Case c) {
    countAModelCall();
    CutAnswerWatch watch = new CutAnswerWatch();
    var prompt =
        chat.prompt().options(options(c)).advisors(watch).system(c.system()).user(c.user());
    if (c.nativeSchema()) {
      prompt = prompt.advisors(AdvisorParams.ENABLE_NATIVE_STRUCTURED_OUTPUT);
    }
    long start = System.nanoTime();
    try {
      var answer = prompt.call().responseEntity(converter(c.answer()));
      long totalMs = msSince(start);
      ChatResponse response = answer.response();
      Generation result = response == null ? null : response.getResult();
      String content = result == null ? "" : nz(result.getOutput().getText());
      return new BlockingRun(
          true,
          totalMs,
          result == null ? NOT_SEEN : nz(result.getMetadata().getFinishReason()),
          watch.answerWasCut(),
          response == null ? null : Tokens.of(response.getMetadata().getUsage()),
          reasoningIn(result, content),
          answer.entity() == null ? "no (null)" : "yes",
          content,
          "");
    } catch (RuntimeException e) {
      return new BlockingRun(
          true,
          msSince(start),
          NOT_SEEN,
          watch.answerWasCut(),
          null,
          NOT_SEEN,
          "no",
          "",
          failure(e));
    }
  }

  private static String reasoningIn(Generation result, String content) {
    int separate = result == null ? 0 : reasoningChars(result);
    if (separate > 0) {
      return "metadata `" + REASONING_METADATA + "` (" + separate + " chars)";
    }
    return content.contains(INLINE_REASONING) ? "inline `<think>` in content" : "none";
  }

  private static int reasoningChars(Generation generation) {
    Object reasoning = generation.getOutput().getMetadata().get(REASONING_METADATA);
    return reasoning instanceof String text ? text.length() : 0;
  }

  static final class StreamRun {
    final long start = System.nanoTime();
    final StringBuilder content = new StringBuilder();
    volatile int chunks;
    volatile long firstChunkMs = -1;
    volatile long firstReasoningMs = -1;
    volatile long firstContentMs = -1;
    volatile long totalMs = -1;
    volatile long headersMs = -1;
    volatile int reasoningChars;
    volatile boolean inlineReasoning;
    volatile String finishReason = NOT_SEEN;
    volatile int finishChunk = -1;
    volatile Tokens tokens;
    volatile int usageChunk = -1;
    volatile boolean usageChunkHasNoChoice;
    volatile boolean completed;
    volatile String schema = NOT_SEEN;
    volatile String parsed = NOT_SEEN;
    volatile String error = "";

    void accept(ChatResponse response) {
      chunks++;
      long now = msSince(start);
      if (firstChunkMs < 0) {
        firstChunkMs = now;
      }
      Generation result = response.getResult();
      if (result != null) {
        read(result, now);
      }
      Tokens seen = Tokens.of(response.getMetadata().getUsage());
      if (seen != null && usageChunk < 0) {
        tokens = seen;
        usageChunk = chunks;
        usageChunkHasNoChoice = result == null;
      }
    }

    private void read(Generation result, long now) {
      String text = nz(result.getOutput().getText());
      content.append(text);
      reasoningChars =
          Math.max(reasoningChars, GuideModelStreamProbeLiveTest.reasoningChars(result));
      inlineReasoning = inlineReasoning || content.indexOf(INLINE_REASONING) >= 0;
      if (firstReasoningMs < 0 && (reasoningChars > 0 || inlineReasoning)) {
        firstReasoningMs = now;
      }
      if (firstContentMs < 0
          && !text.isEmpty()
          && !ModelAnswers.withoutReasoning(content.toString()).isEmpty()) {
        firstContentMs = now;
      }
      String finish = result.getMetadata().getFinishReason();
      if (finish != null && !finish.isBlank() && finishChunk < 0) {
        finishReason = finish;
        finishChunk = chunks;
      }
    }

    void failed(Throwable e) {
      error = failure(e);
    }

    void completed() {
      completed = true;
    }

    boolean showsReasoning() {
      return reasoningChars > 0 || inlineReasoning;
    }

    String reasoning() {
      if (reasoningChars > 0) {
        return "metadata `"
            + REASONING_METADATA
            + "`, the text so far on every chunk ("
            + reasoningChars
            + " chars)";
      }
      return inlineReasoning ? "inline `<think>` in content" : "none";
    }

    boolean cut() {
      return "length".equalsIgnoreCase(finishReason) || "max_tokens".equalsIgnoreCase(finishReason);
    }
  }

  private Flux<ChatResponse> stream(Case c, StreamRun run) {
    BeanOutputConverter<?> converter = converter(c.answer());
    OpenAiChatOptions.Builder options = options(c);
    String user = c.user();
    if (c.nativeSchema()) {
      options.outputSchema(converter.getJsonSchema());
      run.schema = "response_format json_schema in the options";
    } else {
      user = user + System.lineSeparator() + converter.getFormat();
      run.schema = "schema as text in the user message";
    }
    return chat.prompt().options(options).system(c.system()).user(user).stream().chatResponse();
  }

  private StreamRun streamOnce(Case c) {
    countAModelCall();
    exchanges.forget();
    StreamRun run = new StreamRun();
    try {
      stream(c, run).doOnNext(run::accept).blockLast(CALL_TIMEOUT.plusSeconds(5));
      run.completed();
      run.parsed = parses(c, run.content.toString());
    } catch (RuntimeException e) {
      run.failed(e);
    }
    run.totalMs = msSince(run.start);
    Exchange exchange = exchanges.last();
    run.headersMs = exchange == null ? -1 : exchange.headersAfterMs();
    return run;
  }

  private static String parses(Case c, String content) {
    try {
      return converter(c.answer()).convert(content) == null ? "no (null)" : "yes";
    } catch (RuntimeException e) {
      return "no (" + e.getClass().getSimpleName() + ")";
    }
  }

  record Cancel(
      long streamEndedBeforeCancelMs,
      boolean headersBeforeDispose,
      int chunksBeforeDispose,
      int chunksAfterDispose,
      long disposeReturnedMs,
      long exchangeEndedMs,
      String ending,
      boolean callCancelled,
      String protocol,
      Tokens tokens,
      String droppedError) {}

  private Cancel cancelAfterTwoSeconds(Case c) throws InterruptedException {
    countAModelCall();
    exchanges.forget();
    AtomicReference<String> dropped = new AtomicReference<>("none");
    Hooks.onErrorDropped(e -> dropped.set(e.getClass().getSimpleName()));
    try {
      StreamRun run = new StreamRun();
      Disposable subscription = stream(c, run).subscribe(run::accept, run::failed, run::completed);
      Thread.sleep(CANCEL_AFTER);
      Exchange atDispose = exchanges.last();
      boolean headersBeforeDispose = atDispose != null && atDispose.headersAt > 0;
      long endedBeforeCancelMs =
          run.completed || !run.error.isEmpty() ? msSince(run.start) - CANCEL_AFTER.toMillis() : -1;
      int chunksBeforeDispose = run.chunks;
      long disposedAt = System.nanoTime();
      subscription.dispose();
      long disposeReturnedMs = msSince(disposedAt);
      Exchange exchange = exchanges.awaitEnd(CALL_TIMEOUT);
      Thread.sleep(LATE_CHUNKS_WINDOW);
      return new Cancel(
          endedBeforeCancelMs,
          headersBeforeDispose,
          chunksBeforeDispose,
          run.chunks - chunksBeforeDispose,
          disposeReturnedMs,
          exchange == null || exchange.endedAt < 0 ? -1 : ms(exchange.endedAt - disposedAt),
          exchange == null ? "no HTTP exchange was seen" : exchange.ending,
          exchange != null && exchange.call.isCanceled(),
          exchange == null ? NOT_SEEN : exchange.protocol,
          run.tokens,
          dropped.get());
    } finally {
      Hooks.resetOnErrorDropped();
    }
  }

  static final class Exchange {
    final Call call;
    final long started = System.nanoTime();
    final CountDownLatch ended = new CountDownLatch(1);
    volatile long headersAt = -1;
    volatile long endedAt = -1;
    volatile String protocol = NOT_SEEN;
    volatile String ending = "still open";

    Exchange(Call call) {
      this.call = call;
    }

    void headersArrived(String protocol) {
      this.protocol = protocol;
      this.headersAt = System.nanoTime();
    }

    synchronized void end(String how) {
      if (endedAt < 0) {
        endedAt = System.nanoTime();
        ending = how;
        ended.countDown();
      }
    }

    long headersAfterMs() {
      return headersAt < 0 ? -1 : ms(headersAt - started);
    }
  }

  static final class ExchangeWatch implements Interceptor {
    private volatile Exchange last;

    @Override
    public Response intercept(Chain chain) throws IOException {
      Exchange exchange = new Exchange(chain.call());
      last = exchange;
      try {
        Response response = chain.proceed(chain.request());
        exchange.headersArrived(response.protocol().toString());
        ResponseBody body = response.body();
        return response
            .newBuilder()
            .body(
                ResponseBody.create(
                    Okio.buffer(new WatchedBody(body.source(), exchange)),
                    body.contentType(),
                    body.contentLength()))
            .build();
      } catch (IOException e) {
        exchange.end("failed before the response headers: " + e.getClass().getSimpleName());
        throw e;
      }
    }

    void forget() {
      last = null;
    }

    Exchange last() {
      return last;
    }

    Exchange awaitEnd(Duration atMost) throws InterruptedException {
      long deadline = System.nanoTime() + atMost.toNanos();
      while (last == null && System.nanoTime() < deadline) {
        Thread.sleep(10);
      }
      Exchange exchange = last;
      if (exchange != null) {
        exchange.ended.await(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
      }
      return exchange;
    }
  }

  static final class WatchedBody extends ForwardingSource {
    private final Exchange exchange;

    WatchedBody(Source body, Exchange exchange) {
      super(body);
      this.exchange = exchange;
    }

    @Override
    public long read(Buffer sink, long byteCount) throws IOException {
      try {
        long read = super.read(sink, byteCount);
        if (read == -1) {
          exchange.end("the body was read to its end");
        }
        return read;
      } catch (IOException e) {
        exchange.end("the read failed: " + e.getClass().getSimpleName());
        throw e;
      }
    }

    @Override
    public void close() throws IOException {
      try {
        super.close();
      } finally {
        exchange.end("the client closed the body");
      }
    }
  }

  record RawStream(
      int status,
      String contentType,
      int dataLines,
      boolean doneLine,
      Map<String, Integer> chunkKeys,
      Map<String, Integer> choiceKeys,
      Map<String, Integer> deltaKeys,
      Map<String, Integer> usageKeys,
      int finishLine,
      int usageLine,
      String error) {}

  private RawStream rawStream(Case c) {
    countAModelCall();
    Map<String, Integer> chunkKeys = new LinkedHashMap<>();
    Map<String, Integer> choiceKeys = new LinkedHashMap<>();
    Map<String, Integer> deltaKeys = new LinkedHashMap<>();
    Map<String, Integer> usageKeys = new LinkedHashMap<>();
    int[] lines = {0, -1, -1};
    boolean[] done = {false};
    int status = -1;
    String contentType = NOT_SEEN;
    String error = "";
    try (HttpClient http = HttpClient.newBuilder().connectTimeout(CALL_TIMEOUT).build()) {
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(BASE_URL + "/chat/completions"))
              .timeout(CALL_TIMEOUT)
              .header("Authorization", "Bearer " + System.getenv("H2_NEBIUS_API_KEY"))
              .header("Content-Type", "application/json")
              .header("Accept", "text/event-stream")
              .POST(HttpRequest.BodyPublishers.ofString(rawBody(c), StandardCharsets.UTF_8))
              .build();
      HttpResponse<Stream<String>> response =
          http.send(request, HttpResponse.BodyHandlers.ofLines());
      status = response.statusCode();
      contentType = response.headers().firstValue("content-type").orElse(NOT_SEEN);
      try (Stream<String> body = response.body()) {
        if (status == 200) {
          body.filter(line -> line.startsWith("data:"))
              .map(line -> line.substring("data:".length()).strip())
              .forEach(data -> {
                if (data.startsWith("[DONE]")) {
                  done[0] = true;
                  return;
                }
                lines[0]++;
                JsonNode chunk = JSON.readTree(data);
                countKeys(chunk, chunkKeys);
                JsonNode choice = chunk.path("choices").path(0);
                countKeys(choice, choiceKeys);
                countKeys(choice.path("delta"), deltaKeys);
                countKeys(chunk.path("usage"), usageKeys);
                if (lines[1] < 0 && filled(choice.path("finish_reason"))) {
                  lines[1] = lines[0];
                }
                if (lines[2] < 0 && filled(chunk.path("usage"))) {
                  lines[2] = lines[0];
                }
              });
        }
      }
    } catch (IOException | RuntimeException e) {
      error = e.getClass().getSimpleName();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      error = e.getClass().getSimpleName();
    }
    return new RawStream(
        status,
        contentType,
        lines[0],
        done[0],
        chunkKeys,
        choiceKeys,
        deltaKeys,
        usageKeys,
        lines[1],
        lines[2],
        error);
  }

  private static String rawBody(Case c) {
    ObjectNode body = JSON.createObjectNode();
    body.put("model", c.model());
    body.put("temperature", 0.0);
    body.put("max_tokens", c.answerTokens() + c.thinkingTokens());
    body.put("stream", true);
    body.putObject("stream_options").put("include_usage", true);
    body.putObject("chat_template_kwargs").put("enable_thinking", c.thinks());
    if (c.thinks()) {
      body.put("thinking_token_budget", c.thinkingTokens());
    }
    var messages = body.putArray("messages");
    messages.addObject().put("role", "system").put("content", c.system());
    BeanOutputConverter<?> converter = converter(c.answer());
    String user = c.user();
    if (c.nativeSchema()) {
      ObjectNode schema = body.putObject("response_format").put("type", "json_schema");
      schema
          .putObject("json_schema")
          .put("name", "json_schema")
          .put("strict", true)
          .set("schema", JSON.readTree(converter.getJsonSchema()));
    } else {
      user = user + System.lineSeparator() + converter.getFormat();
    }
    messages.addObject().put("role", "user").put("content", user);
    return JSON.writeValueAsString(body);
  }

  private static void countKeys(JsonNode node, Map<String, Integer> keys) {
    if (!node.isObject()) {
      return;
    }
    node.properties()
        .forEach(field -> {
          if (filled(field.getValue())) {
            keys.merge(field.getKey(), 1, Integer::sum);
          }
        });
  }

  private static boolean filled(JsonNode value) {
    return !value.isMissingNode()
        && !value.isNull()
        && !(value.isString() && value.asString().isEmpty())
        && !(value.isContainer() && value.isEmpty());
  }

  private String report(
      List<Case> cases,
      Map<Case, BlockingRun> blocking,
      Map<Case, StreamRun> streamed,
      RawStream raw,
      Cancel cancel) {
    StringBuilder out = new StringBuilder();
    out.append("# Guide model stream probe\n\n");
    out.append("- When: ").append(Instant.now()).append('\n');
    out.append("- Provider: ").append(BASE_URL).append('\n');
    out.append("- Fast model (plan): ").append(fastModel).append('\n');
    out.append("- Reasoning model (choose, verify): ").append(reasoningModel).append('\n');
    out.append("- Native JSON schema for choose and verify: ").append(nativeSchema).append('\n');
    out.append("- Per-call timeout: ").append(CALL_TIMEOUT.toSeconds()).append(" s, retries 0\n");
    out.append("- Libraries: Spring AI ")
        .append(version(OpenAiChatModel.class))
        .append(", openai-java ")
        .append(version(com.openai.client.OpenAIClient.class))
        .append(", OkHttp ")
        .append(okHttpVersion())
        .append(", Reactor ")
        .append(version(Flux.class))
        .append('\n');
    out.append("- Model calls in this run: ")
        .append(modelCalls.get())
        .append(" of at most ")
        .append(MAX_MODEL_CALLS)
        .append('\n');
    out.append("- Order: every case streams first; its blocking call runs later, so a provider")
        .append(" prompt cache can only help the blocking call. The first streaming case also")
        .append(" pays the client's start (connection, class loading).\n\n");
    out.append(blockingTable(cases, blocking));
    out.append(streamTable(cases, streamed));
    out.append(streamAnswers(cases, streamed));
    out.append(cancelSection(cancel));
    out.append(rawSection(raw));
    return out.toString();
  }

  private static String blockingTable(List<Case> cases, Map<Case, BlockingRun> blocking) {
    StringBuilder out = new StringBuilder("## Blocking call (`call()`)\n\n");
    out.append("| case | max tokens answer+thinking | total ms | finish reason | cut |")
        .append(" tokens prompt/completion/total | reasoning in ChatResponse | parsed |")
        .append(" content, first 60 chars | error |\n");
    out.append("|---|---|---|---|---|---|---|---|---|---|\n");
    for (Case c : cases) {
      BlockingRun run = blocking.get(c);
      if (!run.ran()) {
        out.append(
            row(
                c.name(),
                tokenRoom(c),
                "not run: its call went to the raw SSE request",
                "",
                "",
                "",
                "",
                "",
                "",
                ""));
        continue;
      }
      out.append(
          row(
              c.name(),
              tokenRoom(c),
              run.totalMs(),
              run.finishReason(),
              run.cut(),
              run.tokens() == null ? "none" : run.tokens().cell(),
              run.reasoning(),
              run.parsed(),
              preview(run.content()),
              run.error()));
    }
    return out.append('\n').toString();
  }

  private static String streamTable(List<Case> cases, Map<Case, StreamRun> streamed) {
    StringBuilder out = new StringBuilder("## Streaming call (`stream().chatResponse()`)\n\n");
    out.append(
        "Times are ms from the subscription; response headers are ms from the HTTP call.\n\n");
    out.append("| case | response headers | first chunk | first reasoning chunk |")
        .append(" first content chunk | total | chunks | reasoning in ChatResponse | error |\n");
    out.append("|---|---|---|---|---|---|---|---|---|\n");
    for (Case c : cases) {
      StreamRun run = streamed.get(c);
      out.append(
          row(
              c.name(),
              seen(run.headersMs),
              seen(run.firstChunkMs),
              seen(run.firstReasoningMs),
              seen(run.firstContentMs),
              seen(run.totalMs),
              run.chunks,
              run.reasoning(),
              run.error));
    }
    return out.append('\n').toString();
  }

  private static String streamAnswers(List<Case> cases, Map<Case, StreamRun> streamed) {
    StringBuilder out = new StringBuilder("## Streaming call: end of the answer\n\n");
    out.append("Spring AI asks for `stream_options.include_usage` on every stream by default.\n\n");
    out.append("| case | finish reason (chunk of chunks) | cut seen in the stream |")
        .append(" tokens prompt/completion/total (chunk of chunks) | usage chunk has no choice |")
        .append(" schema sent as | assembled text parses | content, first 60 chars |\n");
    out.append("|---|---|---|---|---|---|---|---|\n");
    for (Case c : cases) {
      StreamRun run = streamed.get(c);
      out.append(
          row(
              c.name(),
              run.finishChunk < 0
                  ? "none"
                  : run.finishReason + " (" + run.finishChunk + " of " + run.chunks + ")",
              run.cut(),
              run.tokens == null
                  ? "none: the provider sent no usage"
                  : run.tokens.cell() + " (" + run.usageChunk + " of " + run.chunks + ")",
              run.tokens == null ? NOT_SEEN : String.valueOf(run.usageChunkHasNoChoice),
              run.schema,
              run.parsed,
              preview(run.content.toString())));
    }
    return out.append('\n').toString();
  }

  private static String cancelSection(Cancel cancel) {
    StringBuilder out =
        new StringBuilder("## Cancel: choose with thinking on, disposed after 2 s\n\n");
    if (cancel.streamEndedBeforeCancelMs() >= 0) {
      out.append("- The stream had already ended before the cancel: nothing to measure.\n\n");
      return out.toString();
    }
    out.append("- Response headers before the dispose: ")
        .append(cancel.headersBeforeDispose())
        .append('\n');
    out.append("- Chunks before the dispose: ").append(cancel.chunksBeforeDispose()).append('\n');
    out.append("- Chunks after the dispose: ").append(cancel.chunksAfterDispose()).append('\n');
    out.append("- `dispose()` returned after: ").append(cancel.disposeReturnedMs()).append(" ms\n");
    out.append("- HTTP exchange ended after the dispose: ")
        .append(
            cancel.exchangeEndedMs() < 0
                ? "not within " + CALL_TIMEOUT.toSeconds() + " s"
                : cancel.exchangeEndedMs() + " ms")
        .append(" (")
        .append(cancel.ending())
        .append(")\n");
    out.append("- OkHttp call cancelled: ").append(cancel.callCancelled()).append('\n');
    out.append("- Protocol: ").append(cancel.protocol()).append('\n');
    out.append("- Tokens the provider reported for the cancelled call: ")
        .append(cancel.tokens() == null ? "none" : cancel.tokens().cell())
        .append('\n');
    out.append("- Error dropped by Reactor after the dispose: ")
        .append(cancel.droppedError())
        .append("\n\n");
    return out.toString();
  }

  private static String rawSection(RawStream raw) {
    StringBuilder out = new StringBuilder("## Raw SSE (JDK HttpClient, keys only)\n\n");
    if (raw == null) {
      out.append("Not run: Spring AI's ChatResponse carries the reasoning.\n");
      return out.toString();
    }
    out.append("Run for choose with thinking on: no thinking stream showed reasoning in")
        .append(" Spring AI's ChatResponse.\n\n");
    out.append("- HTTP status: ").append(raw.status()).append('\n');
    out.append("- Content type: ").append(raw.contentType()).append('\n');
    out.append("- Data lines: ").append(raw.dataLines()).append(", `[DONE]` line: ");
    out.append(raw.doneLine()).append('\n');
    out.append("- Chunk keys (filled, times seen): ").append(raw.chunkKeys()).append('\n');
    out.append("- Choice keys: ").append(raw.choiceKeys()).append('\n');
    out.append("- Delta keys: ").append(raw.deltaKeys()).append('\n');
    out.append("- Usage keys: ").append(raw.usageKeys()).append('\n');
    out.append("- First data line with `finish_reason`: ").append(seen(raw.finishLine()));
    out.append('\n');
    out.append("- First data line with `usage`: ").append(seen(raw.usageLine())).append('\n');
    out.append("- Error: ").append(raw.error().isEmpty() ? "none" : raw.error()).append('\n');
    return out.toString();
  }

  private static String row(Object... cells) {
    return Stream.of(cells)
        .map(cell -> String.valueOf(cell).replace("|", "\\|").replaceAll("\\s+", " "))
        .collect(Collectors.joining(" | ", "| ", " |\n"));
  }

  private static String tokenRoom(Case c) {
    return c.answerTokens() + "+" + c.thinkingTokens();
  }

  private static String preview(String content) {
    return content.length() <= CONTENT_PREVIEW_CHARS
        ? content
        : content.substring(0, CONTENT_PREVIEW_CHARS);
  }

  private static String seen(long value) {
    return value < 0 ? NOT_SEEN : String.valueOf(value);
  }

  private static String failure(Throwable e) {
    return e.getClass().getSimpleName() + " (" + ModelErrors.category(e) + ")";
  }

  private static String okHttpVersion() {
    try {
      return String.valueOf(Class.forName("okhttp3.OkHttp").getField("VERSION").get(null));
    } catch (ReflectiveOperationException e) {
      return "unknown";
    }
  }

  private static String version(Class<?> type) {
    String version = type.getPackage().getImplementationVersion();
    return version == null ? "unknown" : version;
  }

  private static int thinkingTokens(String variable) {
    int configured = Integer.parseInt(env(variable, "0"));
    return configured > 0 ? configured : THINKING_TOKENS_WHEN_ON;
  }

  private static String env(String name, String fallback) {
    String value = System.getenv(name);
    return value == null || value.isBlank() ? fallback : value.strip();
  }

  private static String onOff(boolean on) {
    return on ? "on" : "off";
  }

  private static String nz(String text) {
    return text == null ? "" : text;
  }

  private static long msSince(long startNanos) {
    return ms(System.nanoTime() - startNanos);
  }

  private static long ms(long nanos) {
    return TimeUnit.NANOSECONDS.toMillis(nanos);
  }
}
