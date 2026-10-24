package ai.helper2.platform.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.OpenAiChatOptions;

class CallOptionsTest {

    @Test
    void thinkingOffIsSentAsAChatTemplateFlag() {
        OpenAiChatOptions off = CallOptions.of("nano", 400, false).build();
        OpenAiChatOptions on = CallOptions.of("super", null, true).build();

        assertThat(off.getExtraBody()).containsEntry("chat_template_kwargs", Map.of("enable_thinking", false));
        assertThat(off.getModel()).isEqualTo("nano");
        assertThat(off.getMaxTokens()).isEqualTo(400);
        assertThat(off.getTemperature()).isZero();
        assertThat(on.getExtraBody() == null || on.getExtraBody().isEmpty()).isTrue();
    }
}
