package ai.searchavo.sources.pypi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.searchavo.platform.resilience.Resilience;
import ai.searchavo.scout.admission.AdmissionFixtures;
import ai.searchavo.sources.SourceFixtures;
import ai.searchavo.sources.config.CollectorProperties;
import ai.searchavo.sources.support.SourceSettings;
import ai.searchavo.testing.TestHttp;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class PypiCollectorsTest {

  static final RestClient HTTP =
      TestHttp.restClientBuilder(Duration.ofSeconds(2), ObservationRegistry.NOOP).build();

  private static PypiCollector create(boolean enabled, String key, String project) {
    return PypiCollectors.create(
        new SourceSettings(enabled, null, key, Duration.ofSeconds(60), Duration.ZERO, null),
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

    assertThat(create(true, key, "searchavoai").getDescriptor().getEnabled()).isTrue();
  }

  @Test
  void onWithoutAKeyOrAProjectStopsStartup() {
    String key = GoogleKeys.base64(GoogleKeys.keyJson(GoogleServiceAccount.TOKEN_URI.toString()));

    assertThatThrownBy(() -> create(true, null, "searchavoai"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("SA_PYPI_BIGQUERY_KEY");
    assertThatThrownBy(() -> create(true, key, " "))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("SA_PYPI_BIGQUERY_PROJECT");
  }

  @Test
  void onWithABrokenKeyStopsStartupWithoutShowingTheKey() {
    assertThatThrownBy(() -> create(true, "c2VjcmV0LXZhbHVl", "searchavoai"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining("c2VjcmV0LXZhbHVl")
        .hasMessageNotContaining("secret-value");
  }
}
