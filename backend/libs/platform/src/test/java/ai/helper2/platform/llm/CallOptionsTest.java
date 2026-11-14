package ai.helper2.platform.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.OpenAiChatOptions;

class CallOptionsTest {

  @Test
  void thinkingOffIsSentAsAChatTemplateFlag() {
    OpenAiChatOptions off = CallOptions.of("nano", 400).build();

    assertThat(off.getExtraBody())
        .containsEntry("chat_template_kwargs", Map.of("enable_thinking", false))
        .doesNotContainKey("thinking_token_budget");
    assertThat(off.getModel()).isEqualTo("nano");
    assertThat(off.getMaxTokens()).isEqualTo(400);
    assertThat(off.getTemperature()).isZero();
  }

  @Test
  void aThinkingBudgetIsSentAndAddedToTheRoomForTheAnswer() {
    OpenAiChatOptions on = CallOptions.thinking("super", 400, 512).build();

    assertThat(on.getExtraBody())
        .containsEntry("chat_template_kwargs", Map.of("enable_thinking", true))
        .containsEntry("thinking_token_budget", 512);
    assertThat(on.getMaxTokens()).isEqualTo(912);
  }

  @Test
  void aThinkingBudgetOfZeroIsThinkingOff() {
    OpenAiChatOptions off = CallOptions.thinking("super", 400, 0).build();

    assertThat(off.getExtraBody())
        .containsEntry("chat_template_kwargs", Map.of("enable_thinking", false));
    assertThat(off.getMaxTokens()).isEqualTo(400);
  }
}
