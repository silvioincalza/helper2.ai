package ai.helper2.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.helper2.shared.ResourceType;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * An answer with no pick spends no AI answer (ADR 0017), seen as a visitor sees it: ask on the
 * guide stream, then read what is left on GET /me. Real PostgreSQL and Redis, a scripted model.
 */
@TestPropertySource(
    properties = {
      "spring.ai.model.chat=fake",
      "helper2.llm.provider-label=nebius-token-factory",
      "helper2.llm.fast-model=fake-nano",
      "helper2.llm.reasoning-model=fake-super",
      "helper2.llm.native-schema=false",
      "helper2.guide.client-daily-questions=3",
      "helper2.guide.client-daily-free-answers=2",
    })
class GuideFreeAnswersApiIT extends ApiTestBase {

  static final String TITLE = "Polyglot Translate";
  static final String QUOTE = "converts whole documents between thirty languages in one click";
  static final AtomicBoolean PICKS = new AtomicBoolean();
  static final AtomicBoolean MODEL_DOWN = new AtomicBoolean();
  private static final Pattern CANDIDATE =
      Pattern.compile("<candidate key=\"(c\\d+)\"[^>]*>\\n([^\\n]*)");
  private static final Pattern PICK = Pattern.compile("<pick key=\"(c\\d+)\">");

  @TestConfiguration(proxyBeanMethods = false)
  static class FakeLlm {
    @Bean
    ChatModel chatModel() {
      return new ChatModel() {
        @Override
        public ChatResponse call(Prompt prompt) {
          return new ChatResponse(List.of(new Generation(new AssistantMessage(reply(prompt)))));
        }
      };
    }
  }

  private static String reply(Prompt prompt) {
    if (MODEL_DOWN.get()) {
      throw new IllegalStateException("provider down");
    }
    String text = prompt.getContents();
    if ("fake-nano".equals(prompt.getOptions().getModel())) {
      return "{\"searchQuery\":\"document translation\",\"constraints\":[],\"wantsReading\":false}";
    }
    if (text.contains("PICKS:")) {
      Matcher m = PICK.matcher(text);
      return m.find()
          ? "{\"verdicts\":[{\"key\":\"" + m.group(1) + "\",\"supported\":true}]}"
          : "{\"verdicts\":[]}";
    }
    if (!PICKS.get()) {
      return "{\"picks\":[],\"prompt\":null,\"promptBasedOn\":[]}";
    }
    Matcher m = CANDIDATE.matcher(text);
    String key = null;
    while (m.find()) {
      if (m.group(2).startsWith(TITLE)) {
        key = m.group(1);
      }
    }
    return """
    {"picks":[{"candidateKey":"%s","reason":"Translates documents.","quote":"%s","checks":[]}],
     "prompt":null,"promptBasedOn":[]}
    """
        .formatted(key, QUOTE);
  }

  @BeforeEach
  void scriptTheSource() {
    PICKS.set(false);
    MODEL_DOWN.set(false);
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

  @Test
  void anAnswerWithNoPickLeavesEveryAnswerOfTheDay() throws Exception {
    String ip = "10.9.0.1";
    assertThat(left(ip)).isEqualTo(3);

    askWithoutPick(ip, "translate a poem into Klingon");

    assertThat(left(ip)).isEqualTo(3);
  }

  @Test
  void anAnswerWithAPickSpendsOneAndTheSameQuestionFromTheCacheSpendsNone() throws Exception {
    String ip = "10.9.0.2";
    PICKS.set(true);

    askWithPick(ip, "translate my lease to German");
    assertThat(left(ip)).isEqualTo(2);

    askWithPick(ip, "translate my lease to German");
    assertThat(left(ip)).as("a cached answer").isEqualTo(2);
  }

  @Test
  void aQuestionTheModelFailsLeavesEveryAnswerOfTheDay() throws Exception {
    String ip = "10.9.0.4";
    MODEL_DOWN.set(true);

    GuideAnswers.ask(mvc, body("translate a menu into Japanese"), csrf().asHeader(), from(ip))
        .value("$.status", "LLM_UNAVAILABLE");

    assertThat(left(ip)).isEqualTo(3);
  }

  @Test
  void pastTheFreeAnswersOfTheDayAnAnswerWithNoPickCounts() throws Exception {
    String ip = "10.9.0.3";

    askWithoutPick(ip, "first question with no fit");
    askWithoutPick(ip, "second question with no fit");
    assertThat(left(ip)).isEqualTo(3);

    askWithoutPick(ip, "third question with no fit");
    assertThat(left(ip)).as("two free answers a day").isEqualTo(2);
  }

  private void askWithoutPick(String ip, String question) throws Exception {
    String json = GuideAnswers.ask(mvc, body(question), csrf().asHeader(), from(ip)).json();
    assertThat(JsonPath.<List<?>>read(json, "$.recommendations[?(@.aiReasoned == true)]"))
        .isEmpty();
    assertThat(JsonPath.<List<?>>read(json, "$.notices[?(@.code == 'GUIDE_DAILY_QUOTA')]"))
        .isEmpty();
  }

  private void askWithPick(String ip, String question) throws Exception {
    GuideAnswers.ask(mvc, body(question), csrf().asHeader(), from(ip))
        .value("$.recommendations[0].aiReasoned", true);
  }

  private int left(String ip) throws Exception {
    String me =
        mvc.perform(get("/api/v1/me").with(from(ip)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(JsonPath.<Integer>read(me, "$.limits.guideQuestions.limit")).isEqualTo(3);
    return JsonPath.<Integer>read(me, "$.limits.guideQuestions.remaining");
  }

  private static RequestPostProcessor from(String ip) {
    return r -> {
      r.setRemoteAddr(ip);
      return r;
    };
  }

  private static String body(String request) {
    return "{\"request\":\"" + request + "\",\"path\":\"USE_AI\"}";
  }
}
