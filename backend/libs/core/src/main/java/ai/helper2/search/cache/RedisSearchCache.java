package ai.helper2.search.cache;

import ai.helper2.platform.resilience.Resilience;
import ai.helper2.search.CachedHits;
import ai.helper2.search.SearchCache;
import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;

public class RedisSearchCache implements SearchCache {

  private final StringRedisTemplate redis;
  private final SourceHitCodec codec;
  private final Resilience resilience;

  public RedisSearchCache(StringRedisTemplate redis, ObjectMapper json, Resilience resilience) {
    this.redis = redis;
    this.codec = new SourceHitCodec(json);
    this.resilience = resilience;
  }

  @Override
  public CachedHits get(String key) {
    String raw;
    try {
      raw = resilience.call("redis", () -> redis.opsForValue().get(key));
    } catch (RuntimeException e) {
      throw new IllegalStateException("cache unavailable", e);
    }
    return raw == null ? null : codec.decode(raw);
  }

  @Override
  public void put(String key, CachedHits value, Duration ttl) {
    String encoded = codec.encode(value);
    try {
      resilience.run("redis", () -> redis.opsForValue().set(key, encoded, ttl));
    } catch (RuntimeException e) {
      throw new IllegalStateException("cache unavailable", e);
    }
  }
}
