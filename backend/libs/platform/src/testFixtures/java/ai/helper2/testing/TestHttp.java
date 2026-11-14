package ai.helper2.testing;

import ai.helper2.platform.config.HttpClientProperties;
import ai.helper2.platform.http.ObservedHttp;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import org.springframework.util.unit.DataSize;
import org.springframework.web.client.RestClient;

public final class TestHttp {

  public static final HttpClientProperties SETTINGS =
      new HttpClientProperties(Duration.ofMillis(250), 50, 20, DataSize.ofMegabytes(2));

  public static final int MAX_RESPONSE_BYTES =
      Math.toIntExact(SETTINGS.maxResponseSize().toBytes());

  private TestHttp() {}

  public static RestClient.Builder restClientBuilder(
      Duration timeout, ObservationRegistry registry) {
    return ObservedHttp.restClientBuilder(timeout, registry, SETTINGS);
  }

  public static RestClient.Builder restClientBuilder(
      Duration timeout, Duration deadline, ObservationRegistry registry) {
    return ObservedHttp.restClientBuilder(timeout, deadline, registry, SETTINGS);
  }
}
