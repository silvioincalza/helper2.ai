package ai.helper2.research;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record ResearchStep(
    UUID researchId,
    int seq,
    Kind kind,
    String name,
    String request,
    @Nullable String response,
    @Nullable String error,
    long durationMs,
    @Nullable Integer promptTokens,
    @Nullable Integer completionTokens,
    Instant at) {

  public static final int MAX_TEXT = 200_000;

  public enum Kind {
    MODEL_CALL,
    TOOL_CALL,
    CHECK
  }

  public static String cap(String text) {
    if (text.length() <= MAX_TEXT) {
      return text;
    }
    return text.substring(0, MAX_TEXT) + "\n[cut: " + text.length() + " characters in all]";
  }
}
