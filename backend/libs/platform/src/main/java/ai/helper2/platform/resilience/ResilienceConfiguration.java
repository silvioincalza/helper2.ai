package ai.helper2.platform.resilience;

import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration(proxyBeanMethods = false)
class ResilienceConfiguration {

  @Bean
  Resilience resilience(
      CircuitBreakerRegistry circuits, BulkheadRegistry bulkheads, RetryRegistry retries) {
    return new Resilience4jPolicies(circuits, bulkheads, retries);
  }

  @Bean
  OutboundThrottle outboundThrottle(StringRedisTemplate redis, Resilience resilience) {
    return new RedisOutboundThrottle(redis, resilience);
  }
}
