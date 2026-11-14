package ai.helper2.platform.resilience;

import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;

public final class RedisOutboundThrottle implements OutboundThrottle {

  private final StringRedisTemplate redis;
  private final Resilience resilience;

  public RedisOutboundThrottle(StringRedisTemplate redis, Resilience resilience) {
    this.redis = redis;
    this.resilience = resilience;
  }

  static String key(String host) {
    return "h2:v1:outbound:" + host;
  }

  @Override
  public boolean tryAcquire(String host, Duration interval) {
    try {
      return Boolean.TRUE.equals(
          resilience.call(
              "redis", () -> redis.opsForValue().setIfAbsent(key(host), "1", interval)));
    } catch (RuntimeException e) {
      return false;
    }
  }
}
