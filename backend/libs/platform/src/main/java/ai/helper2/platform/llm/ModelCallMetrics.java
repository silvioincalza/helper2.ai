package ai.helper2.platform.llm;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;

public final class ModelCallMetrics {

  public static final String CALL = "helper2.llm.call";
  public static final String TOKENS = "helper2.llm.tokens";

  private final ObservationRegistry observations;
  private final MeterRegistry meters;

  public ModelCallMetrics(ObservationRegistry observations, MeterRegistry meters) {
    this.observations = observations;
    this.meters = meters;
  }

  public static ModelCallMetrics none() {
    return new ModelCallMetrics(ObservationRegistry.NOOP, new SimpleMeterRegistry());
  }

  public static String promptVersion(String renderedPrompt) {
    try {
      byte[] hash =
          MessageDigest.getInstance("SHA-256")
              .digest(renderedPrompt.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash, 0, 4);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is part of every Java runtime", e);
    }
  }

  public <T> T observe(String step, String model, String prompt, Supplier<T> call) {
    Observation observation =
        Observation.createNotStarted(CALL, observations)
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

  public static final String MISSING_FIELD = "helper2.llm.missing_field";

  public void missingField(String step, String field) {
    meters.counter(MISSING_FIELD, "step", step, "field", field).increment();
  }

  public static final String THINKING_FALLBACK = "helper2.llm.thinking_fallback";

  public enum FallbackReason {
    CUT,
    TIME;

    String tag() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  public void thinkingFallback(String step, FallbackReason reason) {
    meters.counter(THINKING_FALLBACK, "step", step, "reason", reason.tag()).increment();
  }

  public static final String TOKENS_UNKNOWN = "helper2.llm.tokens_unknown";

  public void tokens(String step, String prompt, ChatResponse response) {
    if (response == null) {
      return;
    }
    Usage usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
    tokens(
        step,
        prompt,
        usage == null ? null : usage.getPromptTokens(),
        usage == null ? null : usage.getCompletionTokens());
  }

  public void tokens(
      String step,
      String prompt,
      @Nullable Integer promptTokens,
      @Nullable Integer completionTokens) {
    count(step, prompt, "prompt", promptTokens);
    count(step, prompt, "completion", completionTokens);
  }

  private void count(String step, String prompt, String type, @Nullable Integer n) {
    boolean known = n != null && n > 0;
    meters
        .counter(known ? TOKENS : TOKENS_UNKNOWN, "step", step, "prompt", prompt, "type", type)
        .increment(known ? n : 1);
  }
}
