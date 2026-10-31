package ai.helper2.guide.internal;

import ai.helper2.guide.IndexedQuestion;
import ai.helper2.guide.SimilarQuestionIndex;
import ai.helper2.platform.resilience.Resilience;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Redis adapter of the similar-question index (ADR 0041): one sorted set per reading group,
 * {@code h2:v1:guide-similar:<group hash>}. A member is the answer's cache key (a hash) and the question
 * embedding (float32, little endian, Base64); its score is when the answer expires, in epoch milliseconds.
 * A group holds at most {@code maxPerGroup} questions, the longest-lived ones, and the key expires with its
 * last answer. The nearest-neighbour search is the caller's: a group holds a few vectors, so cosine in the JVM
 * over all of them is exact and cheap.
 *
 * Failure behaviour: every call runs under the {@code redis} policy (ADR 0033). An error or a refused call finds
 * nothing and stores nothing, so an outage only costs the reuse.
 */
class RedisSimilarQuestionIndex implements SimilarQuestionIndex {

    static final int DEFAULT_MAX_PER_GROUP = 32;
    private static final Logger log = LoggerFactory.getLogger(RedisSimilarQuestionIndex.class);

    /** Drop expired, add, keep the newest-expiring maxPerGroup, expire the key with its last member. One round trip. */
    private static final RedisScript<Long> ADD = RedisScript.of("""
        redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', ARGV[1])
        redis.call('ZADD', KEYS[1], ARGV[2], ARGV[3])
        redis.call('ZREMRANGEBYRANK', KEYS[1], 0, -(tonumber(ARGV[4]) + 1))
        local last = redis.call('ZRANGE', KEYS[1], -1, -1, 'WITHSCORES')
        redis.call('PEXPIREAT', KEYS[1], last[2])
        return redis.call('ZCARD', KEYS[1])
        """, Long.class);

    private final StringRedisTemplate redis;
    private final Resilience resilience;
    private final Clock clock;
    private final int maxPerGroup;

    RedisSimilarQuestionIndex(StringRedisTemplate redis, Resilience resilience, Clock clock, int maxPerGroup) {
        this.redis = redis;
        this.resilience = resilience;
        this.clock = clock;
        this.maxPerGroup = maxPerGroup;
    }

    static String redisKey(String group) {
        return "h2:v1:guide-similar:" + group;
    }

    @Override
    public List<IndexedQuestion> questions(String group) {
        Set<TypedTuple<String>> members;
        try {
            members = resilience.call("redis", () ->
                redis.opsForZSet().rangeByScoreWithScores(redisKey(group), clock.millis() + 1, Double.POSITIVE_INFINITY));
        } catch (RuntimeException e) {
            log.debug("similar-question index unavailable: {}", e.getClass().getSimpleName());
            return List.of();
        }
        if (members == null) {
            return List.of();
        }
        List<IndexedQuestion> questions = new ArrayList<>(members.size());
        for (TypedTuple<String> member : members) {
            IndexedQuestion q = decode(member.getValue(), member.getScore());
            if (q != null) {
                questions.add(q);
            }
        }
        return questions;
    }

    @Override
    public void add(String group, IndexedQuestion question) {
        String member = question.getAnswerKey() + ":" + encode(question.getVector());
        long expiresAt = question.getExpiresAt().toEpochMilli();
        try {
            resilience.call("redis", () -> redis.execute(ADD, List.of(redisKey(group)),
                Long.toString(clock.millis()), Long.toString(expiresAt), member, Integer.toString(maxPerGroup)));
        } catch (RuntimeException e) {
            log.debug("similar question not indexed, Redis unavailable: {}", e.getClass().getSimpleName());
        }
    }

    static String encode(float[] vector) {
        ByteBuffer bytes = ByteBuffer.allocate(vector.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        bytes.asFloatBuffer().put(vector);
        return Base64.getEncoder().encodeToString(bytes.array());
    }

    /** Null for a member that does not read: it is skipped, like a miss. */
    static IndexedQuestion decode(String member, Double score) {
        int colon = member == null ? -1 : member.indexOf(':');
        if (colon <= 0 || score == null) {
            return null;
        }
        try {
            ByteBuffer bytes = ByteBuffer.wrap(Base64.getDecoder().decode(member.substring(colon + 1))).order(ByteOrder.LITTLE_ENDIAN);
            float[] vector = new float[bytes.remaining() / Float.BYTES];
            bytes.asFloatBuffer().get(vector);
            return new IndexedQuestion(member.substring(0, colon), vector, Instant.ofEpochMilli(score.longValue()));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
