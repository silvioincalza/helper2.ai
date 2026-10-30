package ai.helper2.platform.resilience;

import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * {@link OutboundThrottle} in Redis: {@code SET h2:v1:outbound:<host> 1 NX PX <interval>} takes the slot, and the key's
 * expiry frees it, on Redis' clock. Fails closed: a Redis error, or the open {@code redis} circuit, answers false, so
 * no replica calls the host while the shared pace cannot be checked.
 */
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
            return Boolean.TRUE.equals(resilience.call("redis", () -> redis.opsForValue().setIfAbsent(key(host), "1", interval)));
        } catch (RuntimeException e) {
            return false;
        }
    }
}
