package ai.helper2.platform.resilience;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.TestcontainersConfiguration;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/** One outbound slot per interval across replicas, on the real Redis: two throttles stand for two replicas. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RedisOutboundThrottleIT {

    @Autowired
    StringRedisTemplate redis;

    private final String host = "test-" + UUID.randomUUID();

    @Test
    void oneCallerPerIntervalWhicheverReplicaAsks() throws InterruptedException {
        OutboundThrottle a = new RedisOutboundThrottle(redis, Resilience.NONE);
        OutboundThrottle b = new RedisOutboundThrottle(redis, Resilience.NONE);

        assertThat(a.tryAcquire(host, Duration.ofMillis(400))).isTrue();
        assertThat(b.tryAcquire(host, Duration.ofMillis(400))).as("the other replica, inside the interval").isFalse();
        assertThat(a.tryAcquire(host, Duration.ofMillis(400))).isFalse();
        assertThat(redis.getExpire(RedisOutboundThrottle.key(host))).isBetween(0L, 1L);

        Thread.sleep(500);
        assertThat(b.tryAcquire(host, Duration.ofMillis(400))).as("after the interval").isTrue();
    }

    @Test
    void whileRedisIsDownNoCallIsAllowed() {
        StringRedisTemplate down = new StringRedisTemplate() {
            @Override
            public ValueOperations<String, String> opsForValue() {
                throw new RedisConnectionFailureException("down");
            }
        };

        assertThat(new RedisOutboundThrottle(down, Resilience.NONE).tryAcquire(host, Duration.ofSeconds(3))).isFalse();
    }

    @Test
    void theKeyNamesOnlyTheHost() {
        assertThat(RedisOutboundThrottle.key("arxiv")).isEqualTo("h2:v1:outbound:arxiv");
    }
}
