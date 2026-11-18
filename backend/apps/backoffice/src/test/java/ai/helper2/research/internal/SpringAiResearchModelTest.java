package ai.helper2.research.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.research.ResearchTool;
import java.util.List;
import org.junit.jupiter.api.Test;

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
}
