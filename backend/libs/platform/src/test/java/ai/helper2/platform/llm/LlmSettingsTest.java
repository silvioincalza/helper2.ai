package ai.helper2.platform.llm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

class LlmSettingsTest {

  static final ChatModel MODEL =
      new ChatModel() {
        @Override
        public ChatResponse call(Prompt prompt) {
          throw new UnsupportedOperationException();
        }
      };

  @Test
  void theFastModelFallsBackToTheReasoningModel() {
    assertThat(new LlmSettings("openai", "", "", "super").fast()).isEqualTo("super");
    assertThat(new LlmSettings("openai", "", "nano", "super").fast()).isEqualTo("nano");
    assertThat(new LlmSettings("openai", "", null, null).fast()).isEmpty();
  }

  @Test
  void aTierNamesItsModel() {
    LlmSettings both = new LlmSettings("openai", "", "nano", "super");
    assertThat(both.model(ModelTier.FAST)).isEqualTo("nano");
    assertThat(both.model(ModelTier.REASONING)).isEqualTo("super");
    assertThat(new LlmSettings("openai", "", "", "super").model(ModelTier.FAST))
        .as("an empty fast model falls back")
        .isEqualTo("super");
    LlmSettings none = new LlmSettings("openai", "", null, null);
    assertThat(none.has(ModelTier.FAST)).isFalse();
    assertThat(none.has(ModelTier.REASONING)).isFalse();
    assertThat(both.has(ModelTier.FAST)).isTrue();
  }

  @Test
  void theProviderLabelFallsBackToTheSpringAiProvider() {
    assertThat(new LlmSettings("openai", " ", "", "").provider()).isEqualTo("openai");
    assertThat(new LlmSettings("openai", "nebius-token-factory", "", "").provider())
        .isEqualTo("nebius-token-factory");
  }

  @Test
  void usableOnlyWithAModelBeanAndAProvider() {
    assertThat(new LlmSettings("openai", "", "", "super").usable(MODEL)).isTrue();
    assertThat(new LlmSettings("none", "", "", "super").usable(MODEL)).isFalse();
    assertThat(new LlmSettings("openai", "", "", "super").usable(null)).isFalse();
  }
}
