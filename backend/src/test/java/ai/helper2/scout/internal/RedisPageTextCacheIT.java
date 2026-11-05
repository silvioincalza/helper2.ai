package ai.helper2.scout.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.helper2.TestcontainersConfiguration;
import ai.helper2.platform.resilience.Resilience;
import ai.helper2.scout.OfficialPage;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;

/** ADR 0056: the cleaned page text lives in Redis only, under a URL hash, for at most a day. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RedisPageTextCacheIT {

  @Autowired StringRedisTemplate redis;

  @Autowired ObjectMapper json;

  @Autowired Resilience resilience;

  @Test
  void theCleanedTextComesBackUnderAUrlHashAndExpiresWithinADay() {
    RedisPageTextCache cache =
        new RedisPageTextCache(redis, json, resilience, Duration.ofHours(24));
    URI target = URI.create("https://acme-" + UUID.randomUUID() + ".example/");
    OfficialPage page =
        new OfficialPage(
            URI.create(target + "pricing"),
            Instant.parse("2026-11-05T10:00:00Z"),
            "Pricing\nPro: $10 per month. Call [phone].",
            false);

    assertThat(cache.get(target)).isNull();
    cache.put(target, List.of(page));

    assertThat(cache.get(target)).containsExactly(page);
    String key = RedisPageTextCache.key(target);
    assertThat(key).doesNotContain("acme").startsWith(RedisPageTextCache.PREFIX);
    assertThat(redis.getExpire(key, TimeUnit.SECONDS))
        .isBetween(
            Duration.ofHours(24).minusMinutes(1).toSeconds(), Duration.ofDays(1).toSeconds());
  }

  @Test
  void aTtlOverADayIsRefused() {
    assertThatThrownBy(() -> new RedisPageTextCache(redis, json, resilience, Duration.ofDays(2)))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
