package ai.helper2.platform.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationPredicate;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ObservabilityConfigurationTest {

    private final ObservationPredicate predicate = new ObservabilityConfiguration().actuatorObservationPredicate();

    private static ServerRequestObservationContext ctx(String uri) {
        return new ServerRequestObservationContext(new MockHttpServletRequest("GET", uri), new MockHttpServletResponse());
    }

    @Test
    void dropsActuatorRequests() {
        assertThat(predicate.test("http.server.requests", ctx("/actuator/health/liveness"))).isFalse();
        assertThat(predicate.test("http.server.requests", ctx("/actuator/prometheus"))).isFalse();
        assertThat(predicate.test("http.server.requests", ctx("/actuator"))).isFalse();
    }

    @Test
    void keepsApplicationRequests() {
        assertThat(predicate.test("http.server.requests", ctx("/api/search"))).isTrue();
        assertThat(predicate.test("http.server.requests", ctx("/actuatorish"))).isTrue();
    }

    @Test
    void keepsNonServerObservations() {
        assertThat(predicate.test("http.client.requests", new Observation.Context())).isTrue();
    }
}
