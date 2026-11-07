package ai.helper2.sources.config;

import ai.helper2.sources.support.SourceSettings;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Every source and collector under {@code helper2.sources.<id>}. The defaults are in
 * application.yaml; only the deadline is derived here (twice the timeout when empty).
 */
@Validated
@ConfigurationProperties("helper2")
public record SourcesProperties(@NotNull Map<String, @Valid SourceSettings> sources) {
  public SourceSettings get(String key) {
    SourceSettings s = sources.get(key);
    if (s == null) {
      throw new IllegalStateException("missing helper2.sources." + key);
    }
    if (s.cacheTtl() == null) {
      throw new IllegalStateException("missing helper2.sources." + key + ".cache-ttl");
    }
    return new SourceSettings(
        s.enabled(),
        s.baseUrl(),
        s.token(),
        s.timeout(),
        s.cacheTtl(),
        s.deadline() == null ? s.timeout().multipliedBy(2) : s.deadline());
  }
}
