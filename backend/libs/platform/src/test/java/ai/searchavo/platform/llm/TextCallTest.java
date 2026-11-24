package ai.searchavo.platform.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.searchavo.platform.resilience.Resilience;
import ai.searchavo.testing.TestPolicies;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

class TextCallTest {

  static final class Unavailable extends RuntimeException {
    Unavailable(String message) {
      super(message);
    }
  }

  final List<Prompt> prompts = new ArrayList<>();

  final ChatModel model =
      new ChatModel() {
        @Override
        public ChatResponse call(Prompt prompt) {
          prompts.add(prompt);
          return new ChatResponse(List.of(new Generation(new AssistantMessage("answer"))));
        }
      };

  TextCall call(int dailyCalls, Resilience resilience) {
    return new TextCall(
        model,
        new BudgetedChat(
            LlmPolicies.CHAT,
            new DailyBudget(dailyCalls, Clock.systemUTC(), "test"),
            ModelCallMetrics.none(),
            resilience),
        "step",
        "nvidia/nemotron",
        800,
        "the system prompt");
  }

  @Test
  void theModelGetsTheSystemPromptTheUserTextAndTheCallOptions() {
    TextCall call = call(1, Resilience.NONE);

    assertThat(call.answer("the user text", "budget used up", Unavailable::new))
        .isEqualTo("answer");

    Prompt sent = prompts.getFirst();
    assertThat(sent.getSystemMessage().getText()).isEqualTo("the system prompt");
    assertThat(sent.getUserMessage().getText()).isEqualTo("the user text");
    assertThat(sent.getOptions().getModel()).isEqualTo("nvidia/nemotron");
    assertThat(sent.getOptions().getMaxTokens()).isEqualTo(800);
    assertThat(sent.getOptions().getTemperature()).isEqualTo(0.0);
  }

  @Test
  void aUserTemplateIsFilledWithItsParameters() {
    call(1, Resilience.NONE)
        .answer(
            user -> user.text("Resource: {title}").param("title", "searchavo"),
            "budget used up",
            Unavailable::new);

    assertThat(prompts.getFirst().getUserMessage().getText()).isEqualTo("Resource: searchavo");
  }

  @Test
  void theCallNamesItsModelAndTheVersionOfItsPrompt() {
    TextCall call = call(1, Resilience.NONE);

    assertThat(call.model()).isEqualTo("nvidia/nemotron");
    assertThat(call.promptVersion()).isEqualTo(ModelCallMetrics.promptVersion("the system prompt"));
  }

  @Test
  void aSpentBudgetIsTheCallersErrorWithItsMessageAndCallsNoModel() {
    TextCall call = call(0, Resilience.NONE);

    assertThatThrownBy(() -> call.answer("user", "daily test budget used up", Unavailable::new))
        .isInstanceOf(Unavailable.class)
        .hasMessage("daily test budget used up");
    assertThat(prompts).isEmpty();
  }

  @Test
  void aRefusedCallIsTheCallersErrorNamingThePolicyAndTheReason() {
    TextCall call = call(1, TestPolicies.llmChatOpen());

    assertThatThrownBy(() -> call.answer("user", "budget used up", Unavailable::new))
        .isInstanceOf(Unavailable.class)
        .hasMessage("model call refused: llm-chat circuit is CIRCUIT_OPEN");
    assertThat(prompts).isEmpty();
  }
}
