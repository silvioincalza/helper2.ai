package ai.helper2.guide.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.guide.IndexedQuestion;
import ai.helper2.testing.MovableClock;
import ai.helper2.testing.TestPolicies;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.RedisScript;

/** An unavailable Redis or an unreadable member only costs the reuse, never the question. */
class RedisSimilarQuestionIndexTest {

    private static final Instant NOW = Instant.parse("2026-10-31T10:00:00Z");

    private static final class DownRedis extends StringRedisTemplate {
        int calls;

        @Override
        public ZSetOperations<String, String> opsForZSet() {
            calls++;
            throw new RedisConnectionFailureException("down");
        }

        @Override
        public <T> T execute(RedisScript<T> script, List<String> keys, Object... args) {
            calls++;
            throw new RedisConnectionFailureException("down");
        }
    }

    @Test
    void aRedisFailureFindsNothingStoresNothingAndIsLeftAloneForAWhile() {
        MovableClock clock = new MovableClock(NOW);
        DownRedis redis = new DownRedis();
        RedisSimilarQuestionIndex index = new RedisSimilarQuestionIndex(redis, TestPolicies.redis(clock), clock, 32);

        assertThat(index.questions("g")).isEmpty();
        index.add("g", new IndexedQuestion("k", new float[] {1f}, NOW.plusSeconds(60)));
        assertThat(index.questions("g")).isEmpty();
        assertThat(redis.calls).as("the open circuit refuses the next calls").isEqualTo(1);
    }

    @Test
    void aVectorReadsBackBitForBitAndAMemberThatDoesNotReadIsSkipped() {
        float[] vector = {0.1f, -2.5f, Float.MAX_VALUE};
        IndexedQuestion read = RedisSimilarQuestionIndex.decode("key:" + RedisSimilarQuestionIndex.encode(vector), 1000.0);

        assertThat(read.getVector()).containsExactly(vector);
        assertThat(read.getAnswerKey()).isEqualTo("key");
        assertThat(read.getExpiresAt()).isEqualTo(Instant.ofEpochMilli(1000));
        assertThat(RedisSimilarQuestionIndex.decode("no-vector", 1000.0)).isNull();
        assertThat(RedisSimilarQuestionIndex.decode("key:not base64!", 1000.0)).isNull();
    }
}
