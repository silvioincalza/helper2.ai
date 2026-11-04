package ai.helper2.platform.http;

import ai.helper2.testing.FixtureServer;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.HttpServerErrorException;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The client sends each request once: retries are the caller's resilience policy (ADR 0033), never hidden in the client.
 */
class ObservedHttpRetryTest {

    final FixtureServer server = new FixtureServer();

    @AfterEach
    void stop() {
        server.close();
    }

    @Test
    void a503IsAnsweredWithoutASecondRequest() {
        server.route("/busy", new FixtureServer.Reply(503, "{}", Map.of(), 0));

        assertThatThrownBy(() -> ObservedHttp.restClientBuilder(Duration.ofSeconds(2), ObservationRegistry.NOOP).baseUrl(server.baseUrl())
            .build().get().uri("/busy").retrieve().body(String.class)).isInstanceOf(HttpServerErrorException.class);
        assertThat(server.requests).hasSize(1);
    }
}
