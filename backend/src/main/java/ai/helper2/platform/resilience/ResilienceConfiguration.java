package ai.helper2.platform.resilience;

import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The registries come from resilience4j-spring-boot4, configured under {@code resilience4j.*} (application.yaml),
 * which also creates every configured instance at startup and binds its meters (resilience4j-micrometer).
 */
@Configuration(proxyBeanMethods = false)
class ResilienceConfiguration {

    @Bean
    Resilience resilience(CircuitBreakerRegistry circuits, BulkheadRegistry bulkheads, RetryRegistry retries) {
        return new Resilience4jPolicies(circuits, bulkheads, retries);
    }
}
