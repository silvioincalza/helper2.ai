package ai.helper2.platform.cache;

import ai.helper2.platform.resilience.Resilience;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;

final class JdbcSharedCache implements SharedCache {

  private static final Logger log = LoggerFactory.getLogger(JdbcSharedCache.class);

  private final String name;
  private final JdbcClient jdbc;
  private final Resilience resilience;
  private final MeterRegistry meters;

  JdbcSharedCache(String name, JdbcClient jdbc, Resilience resilience, MeterRegistry meters) {
    this.name = name;
    this.jdbc = jdbc;
    this.resilience = resilience;
    this.meters = meters;
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public @Nullable String get(String key) {
    String value;
    try {
      value =
          resilience.call(
              SharedCaches.POLICY,
              () ->
                  jdbc.sql(
                          """
                          SELECT value FROM ai_cache
                          WHERE cache_name = :cache AND key_hash = decode(:key, 'hex') AND expires_at > now()
                          """)
                      .param("cache", name)
                      .param("key", key)
                      .query(String.class)
                      .optional()
                      .orElse(null));
    } catch (RuntimeException e) {
      log.debug("{} cache unavailable: {}", name, e.getClass().getSimpleName());
      value = null;
    }
    meters
        .counter("helper2.shared.cache", "cache", name, "result", value == null ? "miss" : "hit")
        .increment();
    return value;
  }

  @Override
  public void put(String key, String value, Duration ttl) {
    try {
      resilience.run(
          SharedCaches.POLICY,
          () ->
              jdbc.sql(
                      """
                      INSERT INTO ai_cache (cache_name, key_hash, value, expires_at)
                      VALUES (:cache, decode(:key, 'hex'), :value, now() + :ttlMillis * interval '1 millisecond')
                      ON CONFLICT (cache_name, key_hash) DO UPDATE SET value = excluded.value, expires_at = excluded.expires_at
                      """)
                  .param("cache", name)
                  .param("key", key)
                  .param("value", value)
                  .param("ttlMillis", ttl.toMillis())
                  .update());
    } catch (RuntimeException e) {
      log.debug("{} not cached, database unavailable: {}", name, e.getClass().getSimpleName());
    }
  }
}
