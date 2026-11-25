package ai.searchavo.research.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ai.searchavo.platform.llm.DailyBudget;
import ai.searchavo.platform.llm.ModelCallMetrics;
import ai.searchavo.platform.resilience.Resilience;
import ai.searchavo.research.ResearchModel;
import ai.searchavo.research.ResearchTool;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import tools.jackson.databind.json.JsonMapper;

class SpringAiResearchModelTest {

  static final ResearchTool SEARCH =
      new ResearchTool() {
        @Override
        public String name() {
          return "web_search";
        }

        @Override
        public String description() {
          return "Search the web.";
        }

        @Override
        public String parameters() {
          return "{\"type\":\"object\"}";
        }

        @Override
        public Output call(String arguments) {
          return new Output("", null);
        }
      };

  @Test
  void theCallWithNoToolsAsksForTheAnswerSchema() {
    var options = SpringAiResearchModel.options("model", 4096, List.of(), true);

    assertThat(options.getOutputSchema()).contains("verdict").contains("citations");
  }

  @Test
  void aCallWithToolsLeavesTheFormatToTheModel() {
    var options = SpringAiResearchModel.options("model", 4096, List.of(SEARCH), true);

    assertThat(options.getOutputSchema()).isNull();
    assertThat(options.getToolCallbacks()).hasSize(1);
  }

  @Test
  void withoutNativeSchemaNoCallAsksForIt() {
    var options = SpringAiResearchModel.options("model", 4096, List.of(), false);

    assertThat(options.getOutputSchema()).isNull();
  }

  @Test
  void theCallAndItsTokensAreTaggedWithTheHashOfTheInstructions() {
    MeterRegistry meters = new SimpleMeterRegistry();
    ObservationRegistry observations = ObservationRegistry.create();
    observations.observationConfig().observationHandler(new DefaultMeterObservationHandler(meters));
    ChatModel chat =
        prompt ->
            new ChatResponse(
                List.of(new Generation(new AssistantMessage("{}"))),
                ChatResponseMetadata.builder().usage(new DefaultUsage(120, 30)).build());
    String instructions = ResearchConfiguration.prompt();

    new SpringAiResearchModel(
            chat,
            "m",
            instructions,
            4096,
            new DailyBudget(10, Clock.systemUTC(), "review-research"),
            new ModelCallMetrics(observations, meters),
            Resilience.NONE,
            JsonMapper.builder().build(),
            false)
        .next(
            List.of(
                new ResearchModel.Message.Instructions(instructions),
                new ResearchModel.Message.User("Review item")),
            List.of());

    String version = ModelCallMetrics.promptVersion(instructions);
    assertThat(
            meters
                .get(ModelCallMetrics.CALL)
                .tags("step", "research", "prompt", version, "outcome", "ok")
                .timer()
                .count())
        .isEqualTo(1);
    assertThat(
            meters
                .get(ModelCallMetrics.TOKENS)
                .tags("step", "research", "prompt", version, "type", "prompt")
                .counter()
                .count())
        .isEqualTo(120);
  }
}
