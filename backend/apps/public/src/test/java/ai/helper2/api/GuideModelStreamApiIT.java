package ai.helper2.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.helper2.shared.ResourceType;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
import reactor.core.publisher.Flux;

@TestPropertySource(
    properties = {
      "spring.ai.model.chat=fake",
      "helper2.llm.provider-label=nebius-token-factory",
      "helper2.llm.fast-model=fake-nano",
      "helper2.llm.reasoning-model=fake-super",
      "helper2.llm.native-schema=false",
      "helper2.guide.model-stream=ON",
    })
class GuideModelStreamApiIT extends ApiTestBase {

  static final String PLAN =
      "{\"searchQuery\":\"document translation\",\"constraints\":[],\"wantsReading\":false}";
  static final String NO_PICK = "{\"picks\":[]}";
  static final String THOUGHT = "The user wants a translator, so I should";

  @TestConfiguration(proxyBeanMethods = false)
  static class StreamingLlm {
    @Bean
    ChatModel chatModel() {
      return new ChatModel() {
        @Override
        public ChatResponse call(Prompt prompt) {
          throw new IllegalStateException("the guide streams its model calls in this test");
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
          String answer = "fake-nano".equals(prompt.getOptions().getModel()) ? PLAN : NO_PICK;
          int middle = answer.length() / 2;
          return Flux.just(
              chunk("", THOUGHT),
              chunk(answer.substring(0, middle), THOUGHT),
              chunk(answer.substring(middle), THOUGHT));
        }
      };
    }

    private static ChatResponse chunk(String text, String reasoningSoFar) {
      return new ChatResponse(
          List.of(
              new Generation(
                  AssistantMessage.builder()
                      .content(text)
                      .properties(Map.of("reasoningContent", reasoningSoFar))
                      .build())));
    }
  }

  private String stream(String question) throws Exception {
    MvcResult result =
        mvc.perform(
                post("/api/v1/guide/stream")
                    .with(csrf().asHeader())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"request\":\"" + question + "\"}"))
            .andExpect(request().asyncStarted())
            .andReturn();
    result.getAsyncResult(10_000);
    mvc.perform(asyncDispatch(result)).andExpect(status().isOk());
    return result.getResponse().getContentAsString();
  }

  private static List<String> eventsWithData(String stream, String event) {
    Matcher m = Pattern.compile("event:" + event + "\\ndata:(.*)\\n").matcher(stream);
    return m.results().map(r -> r.group(1)).toList();
  }

  @Test
  void aStreamedQuestionSaysWhenEachModelCallReasonsAndWritesAndShowsNoneOfItsText()
      throws Exception {
    FAKE.set(q ->
        List.of(
            fakeHit(
                "https://github.com/acme/polyglot",
                "Polyglot Translate",
                "Polyglot Translate converts whole documents between thirty languages",
                ResourceType.PRODUCT,
                "MIT",
                null,
                null,
                0)));

    String stream = stream("translate a brochure to Italian with a streamed model");

    assertThat(
            Pattern.compile("event:(\\w+)").matcher(stream).results().map(r -> r.group(1)).toList())
        .containsExactly(
            "reasoning",
            "writing",
            "planned",
            "understood",
            "retrieved",
            "reasoning",
            "writing",
            "chosen",
            "validated",
            "answer");
    assertThat(eventsWithData(stream, "reasoning"))
        .containsExactly("{\"step\":\"plan\"}", "{\"step\":\"choose\"}")
        .allSatisfy(data -> ContractValidator.assertMatches("GuideStreamModelStep", data));
    assertThat(eventsWithData(stream, "writing"))
        .containsExactly("{\"step\":\"plan\"}", "{\"step\":\"choose\"}")
        .allSatisfy(data -> ContractValidator.assertMatches("GuideStreamModelStep", data));
    assertThat(stream).doesNotContain(THOUGHT);
    ContractValidator.assertMatches("GuideResponse", eventsWithData(stream, "answer").getFirst());
    assertThat(eventsWithData(stream, "planned").getFirst())
        .contains("\"searchQuery\":\"document translation\"");
  }
}
