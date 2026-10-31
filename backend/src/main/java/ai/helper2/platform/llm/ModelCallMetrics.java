package ai.helper2.platform.llm;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.Supplier;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;

/**
 * Timer, span and token counters of one model call, for every module that calls a model. An Observation gives
 * both the span and the timer {@code helper2.llm.call} (tags step, model, prompt, outcome, plus Micrometer's own
 * {@code error}); {@code helper2.llm.tokens} counts tokens by step, prompt and type. Tags are fixed vocabularies,
 * configured model ids and prompt versions; never a prompt, a query or a user.
 */
public final class ModelCallMetrics {

    public static final String CALL = "helper2.llm.call";
    public static final String TOKENS = "helper2.llm.tokens";

    private final ObservationRegistry observations;
    private final MeterRegistry meters;

    public ModelCallMetrics(ObservationRegistry observations, MeterRegistry meters) {
        this.observations = observations;
        this.meters = meters;
    }

    /** No-op tracing, throwaway meters: for callers and tests that do not look at them. */
    public static ModelCallMetrics none() {
        return new ModelCallMetrics(ObservationRegistry.NOOP, new SimpleMeterRegistry());
    }

    /**
     * The version of a rendered system prompt: the first 8 hex characters of its SHA-256. It changes with every edit
     * of the prompt or of a limit rendered into it, with no version number to keep by hand.
     */
    public static String promptVersion(String renderedPrompt) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(renderedPrompt.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 4);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every Java runtime", e);
        }
    }

    /** One attempt: a retry is a second call to the provider, with its own latency, so it is observed again. */
    public <T> T observe(String step, String model, String prompt, Supplier<T> call) {
        Observation observation = Observation.createNotStarted(CALL, observations)
            .lowCardinalityKeyValue("step", step)
            .lowCardinalityKeyValue("model", model)
            .lowCardinalityKeyValue("prompt", prompt)
            .start();
        try (Observation.Scope ignored = observation.openScope()) {
            T result = call.get();
            observation.lowCardinalityKeyValue("outcome", "ok");
            return result;
        } catch (RuntimeException e) {
            observation.lowCardinalityKeyValue("outcome", ModelErrors.category(e));
            observation.error(e);
            throw e;
        } finally {
            observation.stop();
        }
    }

    /** Tokens the provider reports; nothing is counted when it reports none. */
    public void tokens(String step, String prompt, ChatResponse response) {
        Usage usage = response == null || response.getMetadata() == null ? null : response.getMetadata().getUsage();
        if (usage == null) {
            return;
        }
        count(step, prompt, "prompt", usage.getPromptTokens());
        count(step, prompt, "completion", usage.getCompletionTokens());
    }

    private void count(String step, String prompt, String type, Integer n) {
        if (n != null && n > 0) {
            meters.counter(TOKENS, "step", step, "prompt", prompt, "type", type).increment(n);
        }
    }
}
