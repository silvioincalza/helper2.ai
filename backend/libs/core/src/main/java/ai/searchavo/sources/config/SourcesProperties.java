package ai.searchavo.sources.config;

import ai.searchavo.sources.support.SourceSettings;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("searchavo")
public record SourcesProperties(@NotNull Map<String, @Valid SourceSettings> sources) {
  public SourceSettings get(String key) {
    SourceSettings s = sources.get(key);
    if (s == null) {
      throw new IllegalStateException("missing searchavo.sources." + key);
    }
    if (s.cacheTtl() == null) {
      throw new IllegalStateException("missing searchavo.sources." + key + ".cache-ttl");
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
