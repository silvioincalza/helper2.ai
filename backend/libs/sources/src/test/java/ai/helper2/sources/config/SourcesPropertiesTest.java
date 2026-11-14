package ai.helper2.sources.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.helper2.sources.support.SourceSettings;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SourcesPropertiesTest {

  @Test
  void theDeadlineIsTwiceTheTimeoutUnlessSet() {
    Duration ttl = Duration.ofMinutes(30);
    var properties =
        new SourcesProperties(
            Map.of(
                "plain",
                    new SourceSettings(true, "https://x.example", "", Duration.ofSeconds(10), ttl),
                "set",
                    new SourceSettings(
                        true,
                        "https://x.example",
                        "",
                        Duration.ofSeconds(10),
                        ttl,
                        Duration.ofSeconds(45))));

    assertThat(properties.get("plain").deadline()).isEqualTo(Duration.ofSeconds(20));
    assertThat(properties.get("set").deadline()).isEqualTo(Duration.ofSeconds(45));
  }

  @Test
  void aSourceWithoutItsSettingsStopsStartupWithTheKey() {
    var properties =
        new SourcesProperties(
            Map.of(
                "no-ttl",
                new SourceSettings(true, "https://x.example", "", Duration.ofSeconds(1), null)));

    assertThatThrownBy(() -> properties.get("missing"))
        .hasMessage("missing helper2.sources.missing");
    assertThatThrownBy(() -> properties.get("no-ttl"))
        .hasMessage("missing helper2.sources.no-ttl.cache-ttl");
  }
}
