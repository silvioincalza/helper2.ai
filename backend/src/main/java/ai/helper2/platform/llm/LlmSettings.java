package ai.helper2.platform.llm;

import org.springframework.ai.chat.model.ChatModel;

/**
 * Which LLM the application may call, from {@code spring.ai.model.chat} and {@code helper2.llm.*}.
 * The one place for the fallbacks every module needs: an empty fast model means the reasoning model
 * is used, and no provider label means the Spring AI provider name is shown.
 */
public record LlmSettings(String chatProvider, String providerLabel, String fastModel, String reasoningModel) {

    public LlmSettings {
        chatProvider = nz(chatProvider);
        providerLabel = nz(providerLabel);
        fastModel = nz(fastModel);
        reasoningModel = nz(reasoningModel);
    }

    /** False without a ChatModel bean or with {@code spring.ai.model.chat=none}: callers fall back to their non-LLM path. */
    public boolean usable(ChatModel model) {
        return model != null && !chatProvider.isEmpty() && !"none".equals(chatProvider);
    }

    public boolean hasReasoningModel() {
        return !reasoningModel.isEmpty();
    }

    /** The fast model, or the reasoning model when no fast one is set; empty when neither is. */
    public String fast() {
        return fastModel.isEmpty() ? reasoningModel : fastModel;
    }

    /** Shown to users and recorded with every enrichment run. */
    public String provider() {
        return providerLabel.isEmpty() ? chatProvider : providerLabel;
    }

    private static String nz(String value) {
        return value == null ? "" : value.strip();
    }
}
