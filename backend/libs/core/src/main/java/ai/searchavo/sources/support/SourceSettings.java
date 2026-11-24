package ai.searchavo.sources.support;

import ai.searchavo.shared.Secrets;
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
        + Secrets.mask(token)
        + ", timeout="
        + timeout
        + ", cacheTtl="
        + cacheTtl
        + ", deadline="
        + deadline
        + "]";
  }
}
