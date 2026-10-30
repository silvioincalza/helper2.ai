package ai.helper2.platform.quota;

import ai.helper2.platform.resilience.Resilience;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/** The daily quota counters and their key names (ADR 0037). */
@Configuration(proxyBeanMethods = false)
class QuotaConfiguration {

    @Bean
    QuotaKeys quotaKeys(@Value("${helper2.tavily.quota.key-secret:}") String secret, Clock clock) {
        return new QuotaKeys(secret, clock);
    }

    @Bean
    QuotaCounter quotaCounter(StringRedisTemplate redis, Resilience resilience) {
        return new RedisQuotaCounter(redis, resilience);
    }
}
