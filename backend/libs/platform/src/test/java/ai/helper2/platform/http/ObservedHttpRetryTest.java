package ai.helper2.platform.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.helper2.testing.FixtureServer;
import ai.helper2.testing.TestHttp;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.HttpServerErrorException;

class ObservedHttpRetryTest {

  final FixtureServer server = new FixtureServer();

  @AfterEach
  void stop() {
    server.close();
  }

  @Test
  void a503IsAnsweredWithoutASecondRequest() {
    server.route("/busy", new FixtureServer.Reply(503, "{}", Map.of(), 0));

    assertThatThrownBy(() ->
            TestHttp.restClientBuilder(Duration.ofSeconds(2), ObservationRegistry.NOOP)
                .baseUrl(server.baseUrl())
                .build()
                .get()
                .uri("/busy")
                .retrieve()
                .body(String.class))
        .isInstanceOf(HttpServerErrorException.class);
    assertThat(server.requests).hasSize(1);
  }
}
