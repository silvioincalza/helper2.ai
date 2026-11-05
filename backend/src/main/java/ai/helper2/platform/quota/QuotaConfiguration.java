package ai.helper2.platform.quota;

import ai.helper2.platform.config.QuotaProperties;
import ai.helper2.platform.resilience.Resilience;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/** The daily quota counters and their key names (ADR 0037). */
@Configuration(proxyBeanMethods = false)
class QuotaConfiguration {

  private static final Logger log = LoggerFactory.getLogger(QuotaConfiguration.class);

  @Bean
  QuotaKeys quotaKeys(QuotaProperties quota, Clock clock) {
    QuotaKeys keys = new QuotaKeys(quota.keySecret(), clock);
    if (!keys.hasSecret()) {
      log.warn(
          "H2_QUOTA_KEY_SECRET is not set: client pseudonyms use a random key of this process, so"
              + " rate limits and the guide's daily quota restart with it and count per replica");
    }
    return keys;
  }

  @Bean
  QuotaCounter quotaCounter(StringRedisTemplate redis, Resilience resilience) {
    return new RedisQuotaCounter(redis, resilience);
  }
}
