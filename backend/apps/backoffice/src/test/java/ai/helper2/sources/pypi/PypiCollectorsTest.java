package ai.helper2.sources.pypi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.helper2.platform.resilience.Resilience;
import ai.helper2.scout.admission.AdmissionFixtures;
import ai.helper2.sources.SourceFixtures;
import ai.helper2.sources.config.CollectorProperties;
import ai.helper2.sources.support.SourceSettings;
import ai.helper2.testing.TestHttp;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class PypiCollectorsTest {

  static final RestClient HTTP =
      TestHttp.restClientBuilder(Duration.ofSeconds(2), ObservationRegistry.NOOP).build();

  private static PypiCollector create(boolean enabled, String key, String project) {
    return PypiCollectors.create(
        new SourceSettings(enabled, null, key, Duration.ofSeconds(60), Duration.ZERO),
        new CollectorProperties.Pypi(Duration.ofDays(1), project, 20, 20_000_000_000L),
        HTTP,
        SourceFixtures.CLOCK,
        Resilience.NONE,
        AdmissionFixtures.AS_CONFIGURED);
  }

  @Test
  void offItNeedsNoKey() {
    assertThat(create(false, null, null).getDescriptor().getEnabled()).isFalse();
  }

  @Test
  void onWithAKeyAndAProjectItIsOn() {
    String key = GoogleKeys.base64(GoogleKeys.keyJson(GoogleServiceAccount.TOKEN_URI.toString()));

    assertThat(create(true, key, "helper2ai").getDescriptor().getEnabled()).isTrue();
  }

  @Test
  void onWithoutAKeyOrAProjectStopsStartup() {
    String key = GoogleKeys.base64(GoogleKeys.keyJson(GoogleServiceAccount.TOKEN_URI.toString()));

    assertThatThrownBy(() -> create(true, null, "helper2ai"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("H2_PYPI_BIGQUERY_KEY");
    assertThatThrownBy(() -> create(true, key, " "))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("H2_PYPI_BIGQUERY_PROJECT");
  }

  @Test
  void onWithABrokenKeyStopsStartupWithoutShowingTheKey() {
    assertThatThrownBy(() -> create(true, "c2VjcmV0LXZhbHVl", "helper2ai"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining("c2VjcmV0LXZhbHVl")
        .hasMessageNotContaining("secret-value");
  }
}
