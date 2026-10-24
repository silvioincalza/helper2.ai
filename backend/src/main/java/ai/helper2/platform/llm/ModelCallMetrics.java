package ai.helper2.platform.llm;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.function.Supplier;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;

/**
 * Timer, span and token counters of one model call, for every module that calls a model. An Observation gives
 * both the span and the timer {@code helper2.guide.model.call} (tags step, outcome, model, plus Micrometer's own
 * {@code error}); the name predates the other callers and stays, because dashboards query it. Tags are fixed
 * vocabularies and configured model ids; never a prompt, a query or a user.
 */
public final class ModelCallMetrics {

    public static final String CALL = "helper2.guide.model.call";
    public static final String TOKENS = "helper2.guide.tokens";

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

    /** One attempt: a retry is a second call to the provider, with its own latency, so it is observed again. */
    public <T> T observe(String step, String model, Supplier<T> call) {
        Observation observation = Observation.createNotStarted(CALL, observations)
            .lowCardinalityKeyValue("step", step)
            .lowCardinalityKeyValue("model", model)
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
    public void tokens(String step, ChatResponse response) {
        Usage usage = response == null || response.getMetadata() == null ? null : response.getMetadata().getUsage();
        if (usage == null) {
            return;
        }
        count(step, "prompt", usage.getPromptTokens());
        count(step, "completion", usage.getCompletionTokens());
    }

    private void count(String step, String type, Integer n) {
        if (n != null && n > 0) {
            meters.counter(TOKENS, "step", step, "type", type).increment(n);
        }
    }
}
