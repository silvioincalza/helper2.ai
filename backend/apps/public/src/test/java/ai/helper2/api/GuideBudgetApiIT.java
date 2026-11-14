package ai.helper2.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.helper2.shared.ResourceType;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
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
      "helper2.guide.daily-model-calls=0",
      "helper2.guide.client-daily-questions=1",
    })
class GuideBudgetApiIT extends ApiTestBase {

  static final String TITLE = "Polyglot Translate";
  static final AtomicInteger MODEL_CALLS = new AtomicInteger();

  @TestConfiguration(proxyBeanMethods = false)
  static class ModelThatMustNotBeCalled {
    @Bean
    ChatModel chatModel() {
      return new ChatModel() {
        @Override
        public ChatResponse call(Prompt prompt) {
          MODEL_CALLS.incrementAndGet();
          return new ChatResponse(List.of(new Generation(new AssistantMessage("{}"))));
        }
      };
    }
  }

  @BeforeEach
  void oneCandidate() {
    MODEL_CALLS.set(0);
    FAKE.set(q ->
        List.of(
            fakeHit(
                "https://github.com/acme/polyglot",
                TITLE,
                TITLE + " converts whole documents between thirty languages in one click.",
                ResourceType.PRODUCT,
                "MIT",
                null,
                null,
                0)));
  }

  private String answer(String ip, String question) throws Exception {
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
    String stream = result.getResponse().getContentAsString();
    Matcher m = Pattern.compile("event:answer\\ndata:(.*)\\n").matcher(stream);
    assertThat(m.find()).as("event answer in " + stream).isTrue();
    return m.group(1);
  }

  @Test
  void aSpentGuideBudgetGivesTheTopResultsAndSaysTheGuideCanAnswerNoMoreToday() throws Exception {
    String answer = answer("10.0.11.1", "translate a budget report to Danish");

    ContractValidator.assertMatches("GuideResponse", answer);
    assertThat(answer)
        .contains("\"status\":\"LLM_UNAVAILABLE\"", "\"code\":\"GUIDE_BUDGET_SPENT\"")
        .contains(
            "\"message\":\"The AI guide has answered as many questions as it can today; showing"
                + " search results instead. AI answers are back after 00:00 UTC.\"")
        .contains("\"title\":\"" + TITLE + "\"")
        .doesNotContain("\"aiReasoned\":true");
    Instant retryAt = Instant.parse(JsonPath.<String>read(answer, "$.notices[0].retryAt"));
    assertThat(retryAt)
        .isAfter(Instant.now())
        .isBeforeOrEqualTo(Instant.now().plus(Duration.ofDays(1)));
    assertThat(retryAt.atZone(ZoneOffset.UTC).toLocalTime()).isEqualTo(LocalTime.MIDNIGHT);
    assertThat(MODEL_CALLS).hasValue(0);
  }

  @Test
  void anAnswerRefusedByTheBudgetIsFreeForTheAsker() throws Exception {
    String first = answer("10.0.11.2", "translate a budget plan to Swedish");

    String again = answer("10.0.11.2", "translate a budget plan to Swedish");

    assertThat(first).contains("\"code\":\"GUIDE_BUDGET_SPENT\"");
    assertThat(again)
        .contains("\"code\":\"GUIDE_BUDGET_SPENT\"")
        .doesNotContain("GUIDE_DAILY_QUOTA");
  }
}
