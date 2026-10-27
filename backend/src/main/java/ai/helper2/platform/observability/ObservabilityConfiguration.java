package ai.helper2.platform.observability;

import io.micrometer.observation.ObservationPredicate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.observation.ServerRequestObservationContext;

@Configuration(proxyBeanMethods = false)
class ObservabilityConfiguration {

    /**
     * Drops server observations for actuator endpoints. Kubernetes probes and Prometheus scrapes hit them every few
     * seconds and would otherwise flood the trace backend. Dropping the observation also drops the
     * {@code http.server.requests} timer for those paths, which is acceptable: they carry no user traffic.
     */
    @Bean
    ObservationPredicate actuatorObservationPredicate() {
        return (name, context) -> !(context instanceof ServerRequestObservationContext server
            && isActuator(server.getCarrier() == null ? null : server.getCarrier().getRequestURI()));
    }

    static boolean isActuator(String requestUri) {
        return requestUri != null && (requestUri.equals("/actuator") || requestUri.startsWith("/actuator/"));
    }
}
