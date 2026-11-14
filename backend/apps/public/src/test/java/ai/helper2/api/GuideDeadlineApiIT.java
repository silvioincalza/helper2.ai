package ai.helper2.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.helper2.shared.ResourceType;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
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

@TestPropertySource(
    properties = {
      "spring.ai.model.chat=fake",
      "helper2.llm.provider-label=nebius-token-factory",
      "helper2.llm.fast-model=fake-nano",
      "helper2.llm.reasoning-model=fake-super",
      "helper2.llm.native-schema=false",
      "helper2.guide.question-deadline=PT2S",
      "helper2.guide.model-call-timeout=PT0.9S",
      "helper2.guide.model-call-minimum=PT0.2S",
      "helper2.guide.deadline-margin=PT0.3S",
      "helper2.guide.heartbeat=PT0.5S",
    })
class GuideDeadlineApiIT extends ApiTestBase {

  static final String TITLE = "Polyglot Translate";
  static final String QUOTE = "converts whole documents between thirty languages in one click";
  static final Duration DEADLINE = Duration.ofSeconds(2);
  static final List<String> STEPS_CALLED = new CopyOnWriteArrayList<>();
  static final AtomicReference<String> STEP_THAT_OUTLIVES_THE_DEADLINE = new AtomicReference<>();
  private static final Pattern CANDIDATE =
      Pattern.compile("<candidate key=\"(c\\d+)\"[^>]*>\\n" + Pattern.quote(TITLE));
  private static final Pattern PICK = Pattern.compile("<pick key=\"(c\\d+)\">");

  @TestConfiguration(proxyBeanMethods = false)
  static class ModelThatIgnoresItsTimeout {
    @Bean
    ChatModel chatModel() {
      return new ChatModel() {
        @Override
        public ChatResponse call(Prompt prompt) {
          String step = stepOf(prompt);
          STEPS_CALLED.add(step);
          if (step.equals(STEP_THAT_OUTLIVES_THE_DEADLINE.get())) {
            waitUntilCancelled();
          }
          return new ChatResponse(
              List.of(new Generation(new AssistantMessage(reply(step, prompt.getContents())))));
        }
      };
    }
  }

  private static String stepOf(Prompt prompt) {
    if ("fake-nano".equals(prompt.getOptions().getModel())) {
      return "plan";
    }
    return prompt.getContents().contains("PICKS:") ? "verify" : "choose";
  }

  private static void waitUntilCancelled() {
    try {
      Thread.sleep(DEADLINE.multipliedBy(5));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new UncheckedIOException(new InterruptedIOException("interrupted"));
    }
  }

  private static String reply(String step, String asked) {
    return switch (step) {
      case "plan" ->
          "{\"searchQuery\":\"document translation\",\"constraints\":[],\"wantsReading\":false}";
      case "verify" ->
          "{\"verdicts\":[{\"key\":\"%s\",\"claimsSupported\":true,\"fitsNeed\":true}]}"
              .formatted(keyIn(PICK, asked));
      default ->
          "{\"picks\":[{\"candidateKey\":\"%s\",\"reason\":\"Translates documents.\",\"quote\":\"%s\",\"checks\":[]}]}"
              .formatted(keyIn(CANDIDATE, asked), QUOTE);
    };
  }

  private static String keyIn(Pattern pattern, String asked) {
    Matcher m = pattern.matcher(asked);
    assertThat(m.find()).as("%s in the prompt", pattern).isTrue();
    return m.group(1);
  }

  @BeforeEach
  void oneCandidateAndAModelInTime() {
    STEPS_CALLED.clear();
    STEP_THAT_OUTLIVES_THE_DEADLINE.set(null);
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
  }

  private String stream(String ip, String question) throws Exception {
    MvcResult result =
        mvc.perform(
                post("/api/v1/guide/stream")
                    .with(csrf().asHeader())
                    .with(r -> {
                      r.setRemoteAddr(ip);
                      return r;
                    })
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"request\":\"" + question + "\",\"path\":\"USE_AI\"}"))
            .andExpect(request().asyncStarted())
            .andReturn();
    result.getAsyncResult(15_000);
    mvc.perform(asyncDispatch(result)).andExpect(status().isOk());
    return result.getResponse().getContentAsString();
  }

  private static List<String> events(String stream) {
    return Pattern.compile("event:(\\w+)").matcher(stream).results().map(r -> r.group(1)).toList();
  }

  private static String data(String stream, String event) {
    Matcher m = Pattern.compile("event:" + event + "\\ndata:(.*)\\n").matcher(stream);
    assertThat(m.find()).as("event " + event + " in " + stream).isTrue();
    return m.group(1);
  }

  @Test
  void aReasonCheckThatOutlivesTheDeadlineGivesTheAnswerWithItsPicksNotChecked() throws Exception {
    STEP_THAT_OUTLIVES_THE_DEADLINE.set("verify");

    String stream = stream("10.0.9.1", "translate a deadline brochure to Dutch");

    assertThat(events(stream))
        .containsExactly(
            "planned", "understood", "retrieved", "chosen", "validated", "checked", "answer");
    String answer = data(stream, "answer");
    ContractValidator.assertMatches("GuideResponse", answer);
    assertThat(answer)
        .contains("\"status\":\"OK\"", "\"title\":\"" + TITLE + "\"", "\"aiReasoned\":true")
        .contains("\"reason\":\"Translates documents.\"", "\"reasonCheck\":\"NOT_CHECKED\"")
        .contains("\"notices\":[]");
    assertThat(data(stream, "checked")).contains("\"notChecked\":1");
  }

  @Test
  void anAnswerCutAtTheDeadlineIsNotCachedSoTheNextAskerGetsTheReasonChecked() throws Exception {
    STEP_THAT_OUTLIVES_THE_DEADLINE.set("verify");
    stream("10.0.9.2", "translate a contract to Finnish before the deadline");
    STEP_THAT_OUTLIVES_THE_DEADLINE.set(null);
    STEPS_CALLED.clear();

    String again = stream("10.0.9.2", "translate a contract to Finnish before the deadline");

    assertThat(STEPS_CALLED).containsExactly("plan", "choose", "verify");
    assertThat(data(again, "answer")).contains("\"reasonCheck\":\"SUPPORTED\"");
  }

  @Test
  void aChoiceThatOutlivesTheDeadlineGivesTheTopResultsAndSaysTheGuideRanOutOfTime()
      throws Exception {
    STEP_THAT_OUTLIVES_THE_DEADLINE.set("choose");

    String stream = stream("10.0.9.3", "translate a slow leaflet to Polish");

    assertThat(events(stream)).containsExactly("planned", "understood", "retrieved", "answer");
    String answer = data(stream, "answer");
    ContractValidator.assertMatches("GuideResponse", answer);
    assertThat(answer)
        .contains("\"status\":\"LLM_UNAVAILABLE\"", "\"code\":\"GUIDE_OUT_OF_TIME\"")
        .contains("\"reason\":\"Top search result (no AI reasoning available).\"")
        .doesNotContain("\"aiReasoned\":true");
    assertThat(STEPS_CALLED).containsExactly("plan", "choose");
  }

  @Test
  void aPlanThatOutlivesTheDeadlineHasNothingToAnswerWithAndEndsWith504() throws Exception {
    STEP_THAT_OUTLIVES_THE_DEADLINE.set("plan");

    String stream = stream("10.0.9.4", "translate a stuck manual to Czech");

    assertThat(events(stream)).containsExactly("understood", "error");
    assertThat(data(stream, "error")).contains("\"status\":504", "\"code\":\"timed-out\"");
  }
}
