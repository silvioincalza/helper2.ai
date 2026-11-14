package ai.helper2.sources.support;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

public record SourceSettings(
    boolean enabled,
    String baseUrl,
    String token,
    @NotNull Duration timeout,
    Duration cacheTtl,
    Duration deadline) {
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
