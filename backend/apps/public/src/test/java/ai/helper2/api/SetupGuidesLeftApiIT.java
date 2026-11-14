package ai.helper2.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.helper2.setup.Material;
import ai.helper2.setup.MaterialKind;
import ai.helper2.setup.ToolMaterials;
import ai.helper2.shared.ResourceType;
import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@TestPropertySource(
    properties = {
      "spring.ai.model.chat=fake",
      "helper2.llm.provider-label=nebius-token-factory",
      "helper2.llm.fast-model=fake-nano",
      "helper2.llm.reasoning-model=fake-super",
      "helper2.llm.native-schema=false",
      "helper2.setup.guide.client-daily-guides=2",
      "helper2.setup.guide.step-text=SOURCE_ONLY",
    })
@Import({SetupGuidesLeftApiIT.FakeLlm.class, SetupGuidesLeftApiIT.FakeDocs.class})
class SetupGuidesLeftApiIT extends ApiTestBase {

  static final AtomicInteger MODEL_CALLS = new AtomicInteger();
  static final String QUOTE = "Install acme-x from npm with this one command:";
  static final String README = "\n\n" + QUOTE + "\n\n```bash\nnpm install acme-x\n```\n";
  static final String GUIDE =
      """
      {"sections":[{"kind":"INSTALL","steps":[
        {"text":"Install it.","source":"readme","quote":"%s","code":"npm install acme-x","language":"bash"}]}]}
      """
          .formatted(QUOTE);

  @Autowired JdbcClient jdbc;

  @TestConfiguration(proxyBeanMethods = false)
  static class FakeLlm {
    @Bean
    ChatModel chatModel() {
      return new ChatModel() {
        @Override
        public ChatResponse call(Prompt prompt) {
          MODEL_CALLS.incrementAndGet();
          return new ChatResponse(List.of(new Generation(new AssistantMessage(GUIDE))));
        }
      };
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class FakeDocs {
    @Bean
    @Primary
    ToolMaterials fakeToolMaterials() {
      return ref ->
          ref.getGithubRepo() == null
              ? List.of()
              : List.of(
                  new Material(
                      "readme",
                      MaterialKind.README,
                      URI.create("https://github.com/" + ref.getGithubRepo()),
                      Instant.now(),
                      "# " + ref.getGithubRepo() + README));
    }
  }

  @BeforeEach
  void noModelCallsYet() {
    MODEL_CALLS.set(0);
  }

  @Test
  void theAccountShowsTheSetupGuidesLeft_aNewGuideTakesOneAndAWrittenOneTakesNone()
      throws Exception {
    String ip = "2001:db8:51:1::1";
    String run = UUID.randomUUID().toString().substring(0, 8);
    UUID first = seed("setup-left-a-" + run);
    UUID second = seed("setup-left-b-" + run);
    UUID third = seed("setup-left-c-" + run);

    String before = me(ip);
    assertThat(JsonPath.<Integer>read(before, "$.limits.setupGuides.limit")).isEqualTo(2);
    assertThat(JsonPath.<Integer>read(before, "$.limits.setupGuides.remaining")).isEqualTo(2);
    assertThat(JsonPath.<Integer>read(me(ip), "$.limits.setupGuides.remaining"))
        .as("reading does not count")
        .isEqualTo(2);

    assertThat(JsonPath.<String>read(guide(first, ip), "$.status")).isEqualTo("OK");
    assertThat(JsonPath.<Integer>read(me(ip), "$.limits.setupGuides.remaining")).isEqualTo(1);

    assertThat(JsonPath.<String>read(guide(first, ip), "$.status")).isEqualTo("OK");
    assertThat(MODEL_CALLS).as("the written guide comes from the cache").hasValue(1);
    assertThat(JsonPath.<Integer>read(me(ip), "$.limits.setupGuides.remaining")).isEqualTo(1);

    assertThat(JsonPath.<String>read(guide(second, ip), "$.status")).isEqualTo("OK");
    assertThat(JsonPath.<Integer>read(me(ip), "$.limits.setupGuides.remaining")).isZero();

    String refused = guide(third, ip);
    assertThat(JsonPath.<String>read(refused, "$.status")).isEqualTo("LLM_UNAVAILABLE");
    assertThat(JsonPath.<String>read(refused, "$.unavailable")).isEqualTo("quota");
    String after = me(ip);
    assertThat(JsonPath.<Integer>read(after, "$.limits.setupGuides.remaining")).isZero();
    assertThat(JsonPath.<Integer>read(after, "$.limits.setupGuides.limit")).isEqualTo(2);
    assertThat(JsonPath.<Integer>read(me("2001:db8:52:1::1"), "$.limits.setupGuides.remaining"))
        .as("another visitor of another network")
        .isEqualTo(2);
  }

  private String guide(UUID id, String ip) throws Exception {
    String body =
        mvc.perform(post("/api/v1/resources/{id}/guide", id).with(csrf().asHeader()).with(from(ip)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    ContractValidator.assertMatches("ToolGuide", body);
    return body;
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

  private static RequestPostProcessor from(String ip) {
    return r -> {
      r.setRemoteAddr(ip);
      return r;
    };
  }

  private UUID seed(String title) throws Exception {
    String url = "https://github.com/acme/" + title;
    FAKE.set(q ->
        List.of(
            fakeHit(url, title, title + " server", ResourceType.MCP_SERVER, "MIT", null, null, 0)));
    mvc.perform(get("/api/v1/search").param("q", title)).andExpect(status().isOk());
    AtomicReference<UUID> id = new AtomicReference<>();
    eventually(() -> {
      List<UUID> ids =
          jdbc.sql("SELECT resource_id FROM source_record WHERE source_url = :u")
              .param("u", url)
              .query(UUID.class)
              .list();
      assertThat(ids).isNotEmpty();
      id.set(ids.getFirst());
    });
    return id.get();
  }
}
