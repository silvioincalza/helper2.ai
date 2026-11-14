package ai.helper2.platform.observability;

import io.micrometer.observation.ObservationPredicate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.observation.ServerRequestObservationContext;

@Configuration(proxyBeanMethods = false)
class ObservabilityConfiguration {
  @Bean
  ObservationPredicate actuatorObservationPredicate() {
    return (name, context) ->
        !(context instanceof ServerRequestObservationContext server
            && isActuator(
                server.getCarrier() == null ? null : server.getCarrier().getRequestURI()));
  }

  static boolean isActuator(String requestUri) {
    return requestUri != null
        && (requestUri.equals("/actuator") || requestUri.startsWith("/actuator/"));
  }
}
