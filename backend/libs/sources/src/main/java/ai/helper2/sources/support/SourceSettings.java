package ai.helper2.sources.support;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * Per-source configuration. Tokens are server-side only and never logged. [deadline] is the total
 * time a Scout collector's call may take (ADR 0006 (part was 0006) §2); search sources use their
 * [timeout] as the deadline. Null until {@code SourcesProperties} fills it (default: twice the
 * timeout).
 */
public record SourceSettings(
    boolean enabled,
    String baseUrl,
    String token,
    @NotNull Duration timeout,
    Duration cacheTtl,
    Duration deadline) {

  /** The constructor configuration binds (a record with a second constructor needs it named). */
  @ConstructorBinding
  public SourceSettings {}

  public SourceSettings(
      boolean enabled, String baseUrl, String token, Duration timeout, Duration cacheTtl) {
    this(enabled, baseUrl, token, timeout, cacheTtl, null);
  }

  public boolean hasToken() {
    return token != null && !token.isBlank();
  }

  @Override
  public String toString() {
    return "SourceSettings[enabled="
        + enabled
        + ", baseUrl="
        + baseUrl
        + ", token="
        + (hasToken() ? "***" : "none")
        + ", timeout="
        + timeout
        + ", cacheTtl="
        + cacheTtl
        + ", deadline="
        + deadline
        + "]";
  }
}
