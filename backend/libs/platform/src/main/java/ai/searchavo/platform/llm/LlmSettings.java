package ai.searchavo.platform.llm;

import org.springframework.ai.chat.model.ChatModel;

public record LlmSettings(
    String chatProvider, String providerLabel, String fastModel, String reasoningModel) {

  public LlmSettings {
    chatProvider = nz(chatProvider);
    providerLabel = nz(providerLabel);
    fastModel = nz(fastModel);
    reasoningModel = nz(reasoningModel);
  }

  public boolean usable(ChatModel model) {
    return model != null && !chatProvider.isEmpty() && !"none".equals(chatProvider);
  }

  public boolean hasReasoningModel() {
    return !reasoningModel.isEmpty();
  }

  public String fast() {
    return fastModel.isEmpty() ? reasoningModel : fastModel;
  }

  public String model(ModelTier tier) {
    return tier == ModelTier.FAST ? fast() : reasoningModel;
  }

  public boolean has(ModelTier tier) {
    return !model(tier).isEmpty();
  }

  public String provider() {
    return providerLabel.isEmpty() ? chatProvider : providerLabel;
  }

  private static String nz(String value) {
    return value == null ? "" : value.strip();
  }
}
