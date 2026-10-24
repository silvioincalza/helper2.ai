package ai.helper2.platform.llm;

import java.util.Map;
import org.springframework.ai.openai.OpenAiChatOptions;

/**
 * Options for one model call: temperature 0, a model id, an optional token limit, and whether the model
 * may think before it answers.
 *
 * <p>Nemotron 3 models think by default, and on Token Factory the thinking tokens count against
 * max_tokens and the latency. {@code chat_template_kwargs.enable_thinking=false} (vLLM chat template
 * flag) turns it off. Measured on 27 Oct 2026 with the guide's plan prompt on Nemotron 3 Nano: 526 vs 47
 * completion tokens, 2.5 s vs 0.5 s, same plan.
 */
public final class CallOptions {

    static final String TEMPLATE_KWARGS = "chat_template_kwargs";

    private CallOptions() {
    }

    public static OpenAiChatOptions.Builder of(String model, Integer maxTokens, boolean thinking) {
        // Required: the builder otherwise fills in an OpenAI default model id, which Token Factory rejects.
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model id required");
        }
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder();
        builder.temperature(0.0);
        builder.model(model);
        if (maxTokens != null) {
            builder.maxTokens(maxTokens);
        }
        if (!thinking) {
            builder.extraBody(Map.of(TEMPLATE_KWARGS, Map.of("enable_thinking", false)));
        }
        return builder;
    }
}
