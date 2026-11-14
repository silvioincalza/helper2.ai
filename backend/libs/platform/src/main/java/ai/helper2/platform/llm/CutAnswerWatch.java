package ai.helper2.platform.llm;

import java.util.Locale;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.model.ChatResponse;

public final class CutAnswerWatch implements CallAdvisor {

  private static final Set<String> CUT_AT_THE_LIMIT = Set.of("length", "max_tokens");

  private volatile boolean cut;

  @Override
  public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
    ChatClientResponse response = chain.nextCall(request);
    cut = cutAtTheLimit(response.chatResponse());
    return response;
  }

  public void sawFinishReason(@Nullable String finishReason) {
    cut = isCut(finishReason);
  }

  public boolean answerWasCut() {
    return cut;
  }

  static boolean cutAtTheLimit(ChatResponse response) {
    if (response == null || response.getResult() == null) {
      return false;
    }
    return isCut(response.getResult().getMetadata().getFinishReason());
  }

  static boolean isCut(@Nullable String finishReason) {
    return finishReason != null
        && CUT_AT_THE_LIMIT.contains(finishReason.strip().toLowerCase(Locale.ROOT));
  }

  @Override
  public String getName() {
    return "cut-answer-watch";
  }

  @Override
  public int getOrder() {
    return 0;
  }
}
