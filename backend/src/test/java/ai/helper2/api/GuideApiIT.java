package ai.helper2.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.helper2.search.SourceHit;
import ai.helper2.search.SourceQuery;
import ai.helper2.shared.Audience;
import ai.helper2.shared.CanonicalUrl;
import ai.helper2.shared.DateFact;
import ai.helper2.shared.ResourceType;
import ai.helper2.shared.ValueOrigin;
import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * POST /api/v1/guide/stream end to end with a scripted ChatModel (no network): plan → federated
 * search → choose → validator, against real PostgreSQL and Redis; and the questions back and event
 * dates of ADR 0032.
 */
@TestPropertySource(
    properties = {
      "spring.ai.model.chat=fake",
      "helper2.llm.provider-label=nebius-token-factory",
      "helper2.llm.fast-model=fake-nano",
      "helper2.llm.reasoning-model=fake-super",
      // The fake model returns plain JSON; native structured output needs a real provider's
      // options.
      "helper2.llm.native-schema=false",
      "helper2.rate-limit.guide-per-minute=3",
      "helper2.guide.client-daily-questions=2",
      // Short, so a slow fake model step spans several keep-alive comments.
      "helper2.guide.heartbeat=PT0.1S",
    })
class GuideApiIT extends ApiTestBase {

  static final String TITLE = "Polyglot Translate";
  static final String QUOTE = "converts whole documents between thirty languages in one click";
  static final List<String> MODELS_CALLED = new CopyOnWriteArrayList<>();
  static final AtomicReference<Function<Prompt, String>> REPLY = new AtomicReference<>();
  private static final Pattern CANDIDATE =
      Pattern.compile("<candidate key=\"(c\\d+)\"[^>]*>\\n([^\\n]*)");

  @TestConfiguration(proxyBeanMethods = false)
  static class FakeLlm {
    @Bean
    ChatModel chatModel() {
      return new ChatModel() {
        @Override
        public ChatResponse call(Prompt prompt) {
          MODELS_CALLED.add(prompt.getOptions().getModel());
          return new ChatResponse(
              List.of(new Generation(new AssistantMessage(REPLY.get().apply(prompt)))));
        }
      };
    }
  }

  @BeforeEach
  void scriptTheModelAndTheSource() {
    MODELS_CALLED.clear();
    FAKE.set(q ->
        List.of(
            fakeHit(
                "https://github.com/acme/polyglot",
                TITLE,
                TITLE + " " + QUOTE + ", free for personal use.",
                ResourceType.PRODUCT,
                "MIT",
                null,
                null,
                0)));
    REPLY.set(prompt ->
        "fake-nano".equals(prompt.getOptions().getModel())
            ? "{\"searchQuery\":\"document translation\",\"constraints\":[],\"wantsReading\":false}"
            : prompt.getContents().contains("PICKS:")
                ? supportAll(prompt.getContents())
                : choose(prompt.getContents()));
  }

  /** The reason check: every pick's reason is supported. */
  private static String supportAll(String promptText) {
    Matcher m = Pattern.compile("<pick key=\"(c\\d+)\">").matcher(promptText);
    List<String> verdicts = new java.util.ArrayList<>();
    while (m.find()) {
      verdicts.add("{\"key\":\"" + m.group(1) + "\",\"supported\":true}");
    }
    return "{\"verdicts\":[" + String.join(",", verdicts) + "]}";
  }

  /** One real pick, one invented candidate key, one invented quote on the real candidate. */
  private static String choose(String promptText) {
    String key = null;
    Matcher m = CANDIDATE.matcher(promptText);
    while (m.find()) {
      if (m.group(2).startsWith(TITLE)) {
        key = m.group(1);
      }
    }
    assertThat(key).as("the fake source's hit must be a candidate").isNotNull();
    return """
    {"picks":[
      {"candidateKey":"%1$s","reason":"Translates documents.","quote":"%2$s","checks":[]},
      {"candidateKey":"c99","reason":"Invented.","quote":"%2$s","checks":[]},
      {"candidateKey":"%1$s","reason":"Trusted by 10,000 companies.","quote":"trusted by 10,000 companies worldwide","checks":[]}
    ],"prompt":null,"promptBasedOn":[]}
    """
        .formatted(key, QUOTE);
  }

  @org.springframework.beans.factory.annotation.Autowired
  org.springframework.jdbc.core.simple.JdbcClient jdbc;

  private static RequestPostProcessor from(String ip) {
    return r -> {
      r.setRemoteAddr(ip);
      return r;
    };
  }

  private static String body(String request) {
    return "{\"request\":\"" + request + "\",\"path\":\"USE_AI\"}";
  }

  @Test
  void recommendationsAreValidatedAndEachCallUsesTheRightModel() throws Exception {
    String json =
        GuideAnswers.ask(
                mvc, body("traduci un documento in tedesco"), csrf().asHeader(), from("10.0.0.1"))
            .value("$.status", "PARTIAL")
            .value("$.searchQuery", "document translation")
            .value("$.recommendations.length()", 1)
            .value("$.recommendations[0].title", TITLE)
            .value("$.recommendations[0].quote", QUOTE)
            .value("$.recommendations[0].aiReasoned", true)
            .value("$.recommendations[0].reasonCheck", "SUPPORTED")
            .value("$.recommendations[0].compatibleWith.length()", 0)
            .value("$.model.provider", "nebius-token-factory")
            .value("$.model.fastModel", "fake-nano")
            .value("$.model.reasoningModel", "fake-super")
            .value("$.aiContent.aiGenerated", true)
            .value("$.aiContent.provider", "nebius-token-factory")
            .value("$.aiContent.models[0]", "fake-nano")
            .value("$.aiContent.models[1]", "fake-super")
            .value(
                "$.aiContent.fields",
                org.hamcrest.Matchers.hasItems(
                    "recommendations[].reason",
                    "recommendations[].checks[].constraint",
                    "comparison.rows[].aspect"))
            .value("$.aiContent.policy", "/mission")
            .json();

    ContractValidator.assertMatches("GuideResponse", json);
    assertThat(json).doesNotContain("10,000");
    assertThat(MODELS_CALLED).containsExactly("fake-nano", "fake-super", "fake-super");
  }

  @Test
  void aRepeatedQuestionIsServedFromTheCacheWithoutModelCalls() throws Exception {
    for (int i = 0; i < 2; i++) {
      GuideAnswers.ask(
              mvc, body("translate my contract to French"), csrf().asHeader(), from("10.0.0.2"))
          .value("$.recommendations[0].aiReasoned", true);
    }
    assertThat(MODELS_CALLED).containsExactly("fake-nano", "fake-super", "fake-super");
  }

  @Test
  void anAnswerIsKeptInTheSharedCacheSoARestartDoesNotLoseIt() throws Exception {
    String question = "translate a rental agreement to Portuguese";
    Set<String> before = keys();

    GuideAnswers.ask(mvc, body(question), csrf().asHeader(), from("10.0.0.7"))
        .value("$.recommendations[0].aiReasoned", true);

    Set<String> added = new java.util.HashSet<>(keys());
    added.removeAll(before);
    assertThat(added).hasSize(1);
    String key = added.iterator().next();
    Instant expiresAt =
        jdbc.sql(
                "SELECT expires_at FROM ai_cache WHERE cache_name = 'guide-answer' AND encode(key_hash, 'hex') = :k")
            .param("k", key)
            .query(java.sql.Timestamp.class)
            .single()
            .toInstant();
    assertThat(java.time.Duration.between(Instant.now(), expiresAt).toHours()).isBetween(23L, 24L);
    assertThat(
            jdbc.sql(
                    "SELECT value FROM ai_cache WHERE cache_name = 'guide-answer' AND encode(key_hash, 'hex') = :k")
                .param("k", key)
                .query(String.class)
                .single())
        .contains(QUOTE);
  }

  private Set<String> keys() {
    return Set.copyOf(
        jdbc.sql("SELECT encode(key_hash, 'hex') FROM ai_cache WHERE cache_name = 'guide-answer'")
            .query(String.class)
            .list());
  }

  /**
   * One IPv6 subscriber holds a whole /64: rotating addresses inside it does not reset the quota.
   */
  @Test
  void pastItsDailyQuotaAClientGetsSearchResultsInsteadOfAnError() throws Exception {
    for (String ip : List.of("2001:db8:0:7::1", "2001:db8:0:7::2")) {
      GuideAnswers.ask(mvc, body("quota question from " + ip), csrf().asHeader(), from(ip))
          .value("$.recommendations[0].aiReasoned", true);
    }
    int callsBefore = MODELS_CALLED.size();

    String json =
        GuideAnswers.ask(
                mvc,
                body("one quota question too many"),
                csrf().asHeader(),
                from("2001:db8:0:7::3"))
            .value("$.status", "LLM_UNAVAILABLE")
            .value("$.recommendations[0].aiReasoned", false)
            .exists("$.notices[?(@.code == 'GUIDE_DAILY_QUOTA')]")
            .json();

    ContractValidator.assertMatches("GuideResponse", json);
    assertThat(MODELS_CALLED).hasSize(callsBefore);
    GuideAnswers.ask(
            mvc, body("one quota question too many"), csrf().asHeader(), from("2001:db8:0:8::1"))
        .value("$.recommendations[0].aiReasoned", true);
  }

  @Test
  void theAccountShowsTheGuideQuestionsLeftAndReadingDoesNotCountOne() throws Exception {
    String ip = "2001:db8:0:9::1";
    String before = me(ip);
    assertThat(JsonPath.<Integer>read(before, "$.limits.guideQuestions.limit")).isEqualTo(2);
    assertThat(JsonPath.<Integer>read(before, "$.limits.guideQuestions.remaining")).isEqualTo(2);
    assertThat(JsonPath.<Integer>read(me(ip), "$.limits.guideQuestions.remaining")).isEqualTo(2);

    GuideAnswers.ask(mvc, body("questions left for " + ip), csrf().asHeader(), from(ip))
        .value("$.recommendations[0].aiReasoned", true);

    assertThat(JsonPath.<Integer>read(me(ip), "$.limits.guideQuestions.remaining")).isEqualTo(1);
  }

  private String me(String ip) throws Exception {
    String body =
        mvc.perform(get("/api/v1/me").with(from(ip)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    ContractValidator.assertMatches("Me", body);
    return body;
  }

  @Test
  void modelThatThrowsDegradesInsteadOfFailing() throws Exception {
    REPLY.set(prompt -> {
      throw new IllegalStateException("provider down");
    });
    String json =
        GuideAnswers.ask(
                mvc,
                body("translate a user manual to Spanish"),
                csrf().asHeader(),
                from("10.0.0.3"))
            .value("$.status", "LLM_UNAVAILABLE")
            .value("$.recommendations[0].aiReasoned", false)
            .json();
    ContractValidator.assertMatches("GuideResponse", json);
  }

  /**
   * "Works with" flags (ADR 0013) come with every pick, also a plain search result shown when the
   * model is down.
   */
  @Test
  void aFallbackPickCarriesItsWorksWithFlags() throws Exception {
    FAKE.set(q ->
        List.of(
            fakeHit(
                "https://github.com/acme/flaggedsrv",
                "Flaggedsrv Quuxconnector",
                "Flaggedsrv Quuxconnector lets an assistant read your files.",
                ResourceType.MCP_SERVER,
                "MIT",
                null,
                null,
                0)));
    REPLY.set(prompt -> {
      throw new IllegalStateException("provider down");
    });
    String json =
        GuideAnswers.ask(
                mvc,
                body("flaggedsrv quuxconnector MCP server to build an agent"),
                csrf().asHeader(),
                from("10.0.0.31"))
            .value(
                "$.recommendations[*].aiReasoned",
                org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is(false)))
            .value(
                "$.recommendations[?(@.title == 'Flaggedsrv Quuxconnector')].compatibleWith[*].tool",
                org.hamcrest.Matchers.contains("mcp"))
            .value(
                "$.recommendations[?(@.title == 'Flaggedsrv Quuxconnector')].compatibleWith[*].basis",
                org.hamcrest.Matchers.contains("TYPE"))
            .value(
                "$.recommendations[*].compatibleWith",
                org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.notNullValue()))
            .json();
    ContractValidator.assertMatches("GuideResponse", json);
  }

  /** Runs the streamed guide and returns the raw event stream once the emitter completed. */
  private String stream(String ip, String question) throws Exception {
    return finish(start(ip, question));
  }

  private MvcResult start(String ip, String question) throws Exception {
    return mvc.perform(
            post("/api/v1/guide/stream")
                .with(csrf().asHeader())
                .with(from(ip))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(question)))
        .andExpect(request().asyncStarted())
        .andExpect(header().string("X-Accel-Buffering", "no"))
        .andReturn();
  }

  private String finish(MvcResult result) throws Exception {
    result.getAsyncResult(10_000);
    // The completed emitter is dispatched again, as on a real servlet container (interceptors run
    // again too).
    mvc.perform(asyncDispatch(result)).andExpect(status().isOk());
    return result.getResponse().getContentAsString();
  }

  /** The data line of the first event with this name. */
  private static String data(String stream, String event) {
    Matcher m = Pattern.compile("event:" + event + "\\ndata:(.*)\\n").matcher(stream);
    assertThat(m.find()).as("event " + event + " in " + stream).isTrue();
    return m.group(1);
  }

  @Test
  void streamSendsEachStepInOrderThenTheSameAnswer() throws Exception {
    String stream = stream("10.0.1.1", "translate a brochure to Italian");

    assertThat(
            Pattern.compile("event:(\\w+)").matcher(stream).results().map(r -> r.group(1)).toList())
        .containsExactly(
            "planned", "understood", "retrieved", "chosen", "validated", "checked", "answer");
    ContractValidator.assertMatches("GuideStreamPlanned", data(stream, "planned"));
    ContractValidator.assertMatches("GuideStreamUnderstood", data(stream, "understood"));
    ContractValidator.assertMatches("GuideStreamRetrieved", data(stream, "retrieved"));
    ContractValidator.assertMatches("GuideStreamChosen", data(stream, "chosen"));
    ContractValidator.assertMatches("GuideStreamValidated", data(stream, "validated"));
    ContractValidator.assertMatches("GuideStreamChecked", data(stream, "checked"));
    assertThat(data(stream, "checked"))
        .contains("\"supported\":1", "\"removed\":0", "\"notChecked\":0");
    ContractValidator.assertMatches("GuideResponse", data(stream, "answer"));
    assertThat(data(stream, "planned"))
        .contains("\"searchQuery\":\"document translation\"", "\"model\":\"fake-nano\"");
    assertThat(data(stream, "chosen")).contains("\"proposed\":3", "\"model\":\"fake-super\"");
    assertThat(data(stream, "validated")).contains("\"kept\":1", "\"rejected\":2");
    assertThat(data(stream, "answer")).contains(QUOTE).doesNotContain("10,000");
  }

  /** Results reads why a source was skipped from the stream too, to offer "Try again" or not. */
  @Test
  void streamSaysWhyASourceWasSkipped() throws Exception {
    String retrieved =
        data(stream("10.0.1.9", "translate a short leaflet to Italian"), "retrieved");

    assertThat(
            JsonPath.<List<String>>read(retrieved, "$.sources[?(@.sourceId=='github')].skipReason"))
        .containsExactly("DISABLED");
    assertThat(
            JsonPath.<List<Object>>read(retrieved, "$.sources[?(@.sourceId=='fake-live')].outcome"))
        .containsExactly("SUCCEEDED");
    assertThat(
            JsonPath.<List<Object>>read(
                retrieved, "$.sources[?(@.sourceId=='fake-live')].skipReason"))
        .containsOnlyNulls();
  }

  private static List<String> events(String stream) {
    return Pattern.compile("event:(\\w+)").matcher(stream).results().map(r -> r.group(1)).toList();
  }

  /** The plan call takes a while: the model step is silent, the stream is not. */
  private void slowPlan(long millis) {
    Function<Prompt, String> scripted = REPLY.get();
    REPLY.set(prompt -> {
      if ("fake-nano".equals(prompt.getOptions().getModel())) {
        try {
          Thread.sleep(millis);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
      return scripted.apply(prompt);
    });
  }

  @Test
  void aSlowModelStepIsBridgedByKeepAliveComments() throws Exception {
    slowPlan(700);

    String stream = stream("10.0.1.7", "translate a slow brochure to Dutch");

    assertThat(stream.indexOf(":keep-alive\n\n"))
        .as(stream)
        .isNotNegative()
        .isLessThan(stream.indexOf("event:planned"));
    assertThat(stream.split(":keep-alive", -1).length - 1).isGreaterThanOrEqualTo(3);
    assertThat(events(stream))
        .containsExactly(
            "planned", "understood", "retrieved", "chosen", "validated", "checked", "answer");
    // No comment after the last event: the heartbeat stops with the question.
    assertThat(stream.substring(stream.indexOf("event:answer"))).doesNotContain(":keep-alive");
  }

  @Test
  void anAskerWhoJoinsARunningQuestionGetsKeepAlivesThenTheAnswer() throws Exception {
    slowPlan(1_000);
    String question = "translate a shared slow manual to Danish";

    MvcResult first = start("10.0.1.8", question);
    for (int i = 0; i < 100 && MODELS_CALLED.isEmpty(); i++) {
      Thread.sleep(50);
    }
    assertThat(MODELS_CALLED).as("the first asker's plan call started").isNotEmpty();
    MvcResult joined = start("10.0.1.10", question);
    String joinedStream = finish(joined);
    String firstStream = finish(first);

    assertThat(events(firstStream))
        .containsExactly(
            "planned", "understood", "retrieved", "chosen", "validated", "checked", "answer");
    assertThat(events(joinedStream))
        .as("no step ran for the joined asker")
        .containsExactly("answer");
    assertThat(joinedStream).contains(":keep-alive\n\n");
    assertThat(MODELS_CALLED).containsExactly("fake-nano", "fake-super", "fake-super");
  }

  @Test
  void streamOfACachedAnswerIsTheAnswerAlone() throws Exception {
    stream("10.0.1.2", "translate a menu to German");
    String again = stream("10.0.1.2", "translate a menu to German");

    assertThat(
            Pattern.compile("event:(\\w+)").matcher(again).results().map(r -> r.group(1)).toList())
        .containsExactly("answer");
    assertThat(MODELS_CALLED).containsExactly("fake-nano", "fake-super", "fake-super");
  }

  @Test
  void streamRejectsAQuestionOutsideTheContractBeforeStreaming() throws Exception {
    mvc.perform(
            post("/api/v1/guide/stream")
                .with(csrf().asHeader())
                .with(from("10.0.1.5"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"request\":\"hi\"}"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/v1/guide/stream")
                .with(csrf().asHeader())
                .with(from("10.0.1.5"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"request\":\"" + "x".repeat(501) + "\"}"))
        .andExpect(status().isBadRequest());
    assertThat(MODELS_CALLED).isEmpty();
  }

  /**
   * Blank padding passes the contract's minLength, but what is left after stripping it is too
   * short.
   */
  @Test
  void guideRejectsARequestThatIsTooShortOnceStrippedWith400() throws Exception {
    mvc.perform(
            post("/api/v1/guide/stream")
                .with(csrf().asHeader())
                .with(from("10.0.1.9"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"request\":\"   a   \"}"))
        .andExpect(status().isBadRequest());
    assertThat(MODELS_CALLED).isEmpty();
  }

  @Test
  void eachStreamedQuestionIsChargedOnceDespiteTheAsyncDispatch() throws Exception {
    for (int i = 0; i < 3; i++) {
      assertThat(stream("10.0.1.6", "charged once question " + i)).contains("event:answer");
    }
    mvc.perform(
            post("/api/v1/guide/stream")
                .with(csrf().asHeader())
                .with(from("10.0.1.6"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("charged once question 3")))
        .andExpect(status().isTooManyRequests());
  }

  @Test
  void guideNeedsCsrfAndIsRateLimitedPerClient() throws Exception {
    mvc.perform(
            post("/api/v1/guide/stream")
                .with(from("10.0.0.4"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("no csrf token")))
        .andExpect(status().isForbidden());

    for (int i = 0; i < 3; i++) {
      GuideAnswers.ask(
          mvc, body("rate limited question " + i), csrf().asHeader(), from("10.0.0.5"));
    }
    mvc.perform(
            post("/api/v1/guide/stream")
                .with(csrf().asHeader())
                .with(from("10.0.0.5"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("rate limited question 3")))
        .andExpect(status().isTooManyRequests());
    GuideAnswers.ask(mvc, body("another client"), csrf().asHeader(), from("10.0.0.6"));
  }

  @Test
  void aNameNotInOurSourcesComesAsAMarkedFieldOfItsNoticeNeverInsideTheMessage() throws Exception {
    REPLY.set(prompt ->
        "fake-nano".equals(prompt.getOptions().getModel())
            ? """
            {"intent":"ALTERNATIVES","subjects":["Frobnicator"],"searchQuery":"document translation","constraints":[],
             "wantsReading":false,"webQuery":"","window":null,"clarify":null}\
            """
            : prompt.getContents().contains("PICKS:")
                ? supportAll(prompt.getContents())
                : choose(prompt.getContents()));

    String json =
        GuideAnswers.ask(
                mvc,
                "{\"request\":\"alternatives to Frobnicator\",\"path\":\"USE_AI\"}",
                csrf().asHeader(),
                from("10.0.9.9"))
            .value(
                "$.notices[?(@.code == 'GUIDE_SUBJECT_NOT_FOUND')].subject",
                org.hamcrest.Matchers.contains("Frobnicator"))
            .value(
                "$.notices[?(@.code == 'GUIDE_SUBJECT_NOT_FOUND')].query",
                org.hamcrest.Matchers.contains("document translation"))
            .value(
                "$.aiContent.fields",
                org.hamcrest.Matchers.hasItems("notices[].subject", "notices[].query"))
            .json();

    ContractValidator.assertMatches("GuideResponse", json);
    assertThat(com.jayway.jsonpath.JsonPath.<List<String>>read(json, "$.notices[*].message"))
        .noneMatch(m -> m.contains("Frobnicator") || m.contains("document translation"));
  }

  // ADR 0032 §3 and §5: an ambiguous name asked back once, and a WHEN question about an event over
  // events only. Dates come from records, so the plan call is the only model call.

  private static final Pattern NEED = Pattern.compile("<need>\\n(.*?)\\n</need>", Pattern.DOTALL);
  private static final Pattern ANSWER =
      Pattern.compile("<answer>\\n(.*?)\\n</answer>", Pattern.DOTALL);

  /** The planner of a date question: a reply to our question back names the chosen thing. */
  private static String datePlan(String prompt) {
    Matcher need = NEED.matcher(prompt);
    assertThat(need.find()).as("only the plan call is expected").isTrue();
    if (need.group(1).contains("hackathon")) {
      return """
      {"intent":"WHEN","subjects":[],"searchQuery":"AI hackathon","constraints":[],"wantsReading":true,
       "webQuery":"upcoming AI hackathons","window":null,"clarify":null}\
      """;
    }
    String subject = ANSWER.matcher(prompt).find() ? "gemini cli" : "Gemini";
    return """
    {"intent":"WHEN","subjects":["%1$s"],"searchQuery":"%1$s","constraints":[],"wantsReading":false,
     "webQuery":"%1$s release date","window":null,"clarify":null}\
    """
        .formatted(subject);
  }

  private static SourceHit dated(
      String url,
      String title,
      ResourceType type,
      DateFact published,
      DateFact eventStart,
      int rank) {
    return new SourceHit(
        "fake-live",
        url,
        URI.create(url),
        CanonicalUrl.of(url),
        null,
        title,
        title + " for developers",
        ValueOrigin.SOURCE_PROVIDED,
        type,
        Audience.BUILD_WITH_AI,
        null,
        Set.of(),
        Set.of("ai"),
        null,
        null,
        Set.of(),
        null,
        published,
        DateFact.unknown(),
        List.of(),
        Instant.now(),
        rank,
        eventStart,
        DateFact.unknown(),
        "Zurich");
  }

  private static SourceHit created(
      String url, String title, ResourceType type, String published, int rank) {
    return dated(
        url,
        title,
        type,
        DateFact.fromSource(Instant.parse(published), "fake-live", Instant.now()),
        DateFact.unknown(),
        rank);
  }

  private static SourceHit event(String url, String title, LocalDate start, int rank) {
    return dated(
        url,
        title,
        ResourceType.EVENT,
        DateFact.unknown(),
        DateFact.scheduledDay(start, "fake-live"),
        rank);
  }

  private String askJson(String ip, String json) throws Exception {
    return GuideAnswers.ask(mvc, json, csrf().asHeader(), from(ip)).json();
  }

  @Test
  void anAmbiguousNameIsAskedBackOnceAndTheReplyIsAnsweredFromTheChosenEntry() throws Exception {
    REPLY.set(prompt -> datePlan(prompt.getContents()));
    FAKE.set(q ->
        List.of(
            created(
                "https://ai.google.dev/gemini",
                "Gemini",
                ResourceType.MODEL,
                "2023-12-06T00:00:00Z",
                0),
            created(
                "https://github.com/google-gemini/gemini-cli",
                "google-gemini/gemini-cli",
                ResourceType.LIBRARY,
                "2025-06-25T00:00:00Z",
                1)));
    String question = "{\"request\":\"when was Gemini released?\",\"path\":\"USE_AI\"}";

    String asked = askJson("10.0.9.1", question);
    ContractValidator.assertMatches("GuideResponse", asked);
    // One option per kind: the model, and the first tool named Gemini (the curated seed holds one
    // too).
    assertThat(asked)
        .contains(
            "\"status\":\"NEEDS_CLARIFICATION\"",
            "\"question\":\"Which Gemini do you mean?\"",
            "\"Gemini (model)\"",
            " (tool)\"");
    GuideAnswers.ask(mvc, question, csrf().asHeader(), from("10.0.9.2"))
        .value(
            "$.aiContent.fields",
            org.hamcrest.Matchers.hasItems("clarification.question", "clarification.options[]"))
        .value("$.aiContent.models[0]", "fake-nano");
    assertThat(MODELS_CALLED)
        .as("a plan call each, nothing else")
        .containsExactly("fake-nano", "fake-nano");

    MODELS_CALLED.clear();
    String answered =
        askJson(
            "10.0.9.1",
            "{\"request\":\"when was Gemini released?\",\"path\":\"USE_AI\",\"clarification\":\"gemini cli (tool)\"}");
    ContractValidator.assertMatches("GuideResponse", answered);
    assertThat(answered)
        .contains(
            "\"status\":\"OK\"", "\"title\":\"google-gemini/gemini-cli\"", "Created 2025-06-25")
        .doesNotContain("NEEDS_CLARIFICATION");
    assertThat(MODELS_CALLED).containsExactly("fake-nano");
  }

  @Test
  void aWhenQuestionAboutAnEventSearchesEventsOnlyAndAnswersSoonestFirst() throws Exception {
    REPLY.set(prompt -> datePlan(prompt.getContents()));
    LocalDate today = LocalDate.now(ZoneOffset.UTC);
    List<SourceQuery> queries = new CopyOnWriteArrayList<>();
    FAKE.set(q -> {
      queries.add(q);
      return List.of(
          created(
              "https://github.com/acme/ai-hackathon",
              "ai-hackathon",
              ResourceType.LIBRARY,
              "2025-01-01T00:00:00Z",
              0),
          event("https://example.com/hack-zurich", "AI Hackathon Zurich", today.plusDays(30), 1),
          event("https://example.com/hack-milan", "AI Hackathon Milan", today.plusDays(10), 2));
    });

    String json =
        askJson("10.0.9.3", "{\"request\":\"when is the next AI hackathon?\",\"path\":\"USE_AI\"}");

    ContractValidator.assertMatches("GuideResponse", json);
    assertThat(queries)
        .isNotEmpty()
        .allSatisfy(q -> assertThat(q.getTypes()).containsExactly(ResourceType.EVENT));
    assertThat(json)
        .contains("\"status\":\"OK\"", "\"intent\":\"WHEN\"")
        .doesNotContain("\"title\":\"ai-hackathon\"");
    assertThat(json.indexOf("AI Hackathon Milan")).isLessThan(json.indexOf("AI Hackathon Zurich"));
    assertThat(MODELS_CALLED).containsExactly("fake-nano");
  }
}
