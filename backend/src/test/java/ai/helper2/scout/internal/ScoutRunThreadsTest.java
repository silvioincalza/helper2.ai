package ai.helper2.scout.internal;

import ai.helper2.testing.MdcTraceIds;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A Scout run started from the admin page logs with the trace id of the request that started it (ADR 0026).
 */
class ScoutRunThreadsTest {

    private final ObservationRegistry observations = MdcTraceIds.registry();

    @Test
    void aRunStartedInsideAnObservationSeesItsTraceIdInTheMdc() throws Exception {
        Observation request = Observation.start("http.server.requests", observations);
        CompletableFuture<Optional<String>> seen = new CompletableFuture<>();
        try (Observation.Scope _ = request.openScope()) {
            ScoutConfiguration.RUN_THREADS.execute(() -> seen.complete(Optional.ofNullable(MDC.get(MdcTraceIds.KEY))));
        } finally {
            request.stop();
        }

        assertThat(seen.get(5, TimeUnit.SECONDS)).contains(MdcTraceIds.traceId(request));
    }

    @Test
    void aRunStartedOutsideAnObservationHasNoTraceId() throws Exception {
        CompletableFuture<Optional<String>> seen = new CompletableFuture<>();

        ScoutConfiguration.RUN_THREADS.execute(() -> seen.complete(Optional.ofNullable(MDC.get(MdcTraceIds.KEY))));

        assertThat(seen.get(5, TimeUnit.SECONDS)).isEmpty();
    }

    @Test
    void aRunDoesNotInheritTheAdminsSecurityContext() throws Exception {
        CompletableFuture<Optional<Authentication>> seen = new CompletableFuture<>();
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("admin", null, "ROLE_ADMIN"));
        try {
            Observation.createNotStarted("admin.request", observations).observe(() ->
                ScoutConfiguration.RUN_THREADS.execute(() ->
                    seen.complete(Optional.ofNullable(SecurityContextHolder.getContext().getAuthentication()))));
        } finally {
            SecurityContextHolder.clearContext();
        }

        assertThat(seen.get(5, TimeUnit.SECONDS)).as("only the trace travels, not the sign-in").isEmpty();
    }
}
