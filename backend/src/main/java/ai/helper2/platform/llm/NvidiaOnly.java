package ai.helper2.platform.llm;

import java.util.ArrayList;
import java.util.List;

/**
 * NVIDIA-only (ADR 0014): every model id the application sends to a provider names an NVIDIA model
 * ({@code nvidia/...}). Checked once at startup, so a wrong id in the environment stops the deploy instead of
 * calling another vendor's model. The chat ids are checked when the OpenAI-compatible client is the chat provider
 * (the only client that reaches a provider); the embedding id when embeddings are on (an API key is set).
 */
public final class NvidiaOnly {

    static final String PREFIX = "nvidia/";

    /** The client that sends chat calls to a provider (spring-ai-starter-model-openai). */
    static final String PROVIDER_CLIENT = "openai";

    private NvidiaOnly() {
    }

    /** Fails with {@link IllegalStateException} naming every configured id that is not an NVIDIA model. */
    public static void require(String chatProvider, String chatModel, String fastModel, String reasoningModel,
                               String embeddingKey, String embeddingModel) {
        List<String> wrong = new ArrayList<>();
        if (PROVIDER_CLIENT.equals(chatProvider)) {
            check("spring.ai.openai.chat.model", chatModel, wrong);
            check("helper2.llm.fast-model", fastModel, wrong);
            check("helper2.llm.reasoning-model", reasoningModel, wrong);
        }
        if (!isBlank(embeddingKey)) {
            check("helper2.embedding.model", embeddingModel, wrong);
        }
        if (!wrong.isEmpty()) {
            throw new IllegalStateException("only NVIDIA models (" + PREFIX + "...) may be configured: " + String.join(", ", wrong));
        }
    }

    private static void check(String property, String id, List<String> wrong) {
        if (!isBlank(id) && !id.strip().startsWith(PREFIX)) {
            wrong.add(property + "=" + id.strip());
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
