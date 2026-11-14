package ai.helper2.scout.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import ai.helper2.platform.resilience.Resilience;
import ai.helper2.scout.OfficialPage;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;

class RedisPageTextCacheTest {

  private static final URI TARGET = URI.create("https://acme.example/");
  private static final String PAGE_TEXT = "Pro: $10 per month.";
  private static final OfficialPage PAGE =
      new OfficialPage(
          URI.create("https://acme.example/pricing"),
          Instant.parse("2026-11-12T10:00:00Z"),
          PAGE_TEXT,
          true);

  private final Logger logger = (Logger) LoggerFactory.getLogger(RedisPageTextCache.class);
  private final ListAppender<ILoggingEvent> logged = new ListAppender<>();

  @BeforeEach
  void listenToTheLog() {
    logged.start();
    logger.addAppender(logged);
  }

  @AfterEach
  void stopListening() {
    logger.detachAppender(logged);
  }

  @Test
  void aReadThatFailsIsAMissAndIsLoggedByItsClassOnly() {
    RedisPageTextCache cache = cacheOver(redisDown());

    assertThat(cache.get(TARGET)).isNull();

    assertOneWarningNamesOnlyTheClass("IllegalStateException");
  }

  @Test
  void anUnreadableEntryIsAMissAndIsLoggedByItsClassOnly() {
    RedisPageTextCache cache =
        cacheOver(
            redisHolding(
                "[{\"url\":\"https://acme.example/pricing\",\"retrievedAt\":\""
                    + PAGE_TEXT
                    + "\"}]"));

    assertThat(cache.get(TARGET)).isNull();

    assertOneWarningNamesOnlyTheClass("DateTimeParseException");
  }

  @Test
  void aWriteThatFailsIsLoggedByItsClassOnlyAndTheRunGoesOn() {
    RedisPageTextCache cache = cacheOver(redisDown());

    assertThatCode(() -> cache.put(TARGET, List.of(PAGE))).doesNotThrowAnyException();

    assertOneWarningNamesOnlyTheClass("IllegalStateException");
  }

  private void assertOneWarningNamesOnlyTheClass(String exceptionClass) {
    assertThat(logged.list).hasSize(1);
    ILoggingEvent event = logged.list.getFirst();
    assertThat(event.getLevel()).isEqualTo(Level.WARN);
    assertThat(event.getFormattedMessage())
        .contains(exceptionClass)
        .doesNotContain(PAGE_TEXT)
        .doesNotContain("acme");
    assertThat(event.getThrowableProxy()).isNull();
  }

  private static RedisPageTextCache cacheOver(Resilience resilience) {
    return new RedisPageTextCache(
        new StringRedisTemplate(), JsonMapper.builder().build(), resilience, Duration.ofHours(24));
  }

  private static Resilience redisDown() {
    return new StubbedRedis() {
      @Override
      public <T> T call(String policy, Supplier<T> work) {
        throw new IllegalStateException("redis down at acme.example: " + PAGE_TEXT);
      }
    };
  }

  private static Resilience redisHolding(String raw) {
    return new StubbedRedis() {
      @Override
      @SuppressWarnings("unchecked")
      public <T> T call(String policy, Supplier<T> work) {
        return (T) raw;
      }
    };
  }

  private abstract static class StubbedRedis implements Resilience {
    @Override
    public void require(String policy) {}
  }
}
