package ai.helper2.guide.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.TestcontainersConfiguration;
import ai.helper2.guide.IndexedQuestion;
import ai.helper2.platform.resilience.Resilience;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

/** The similar-question index on the real Redis: one sorted set per reading group, living as long as its answers. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RedisSimilarQuestionIndexIT {

    @Autowired
    StringRedisTemplate redis;

    private final Instant now = Instant.now().plusSeconds(1);

    private RedisSimilarQuestionIndex index(int maxPerGroup) {
        return new RedisSimilarQuestionIndex(redis, Resilience.NONE, Clock.fixed(now, ZoneOffset.UTC), maxPerGroup);
    }

    private static String group() {
        return "test-" + UUID.randomUUID();
    }

    private static IndexedQuestion question(String key, Instant expiresAt, float... vector) {
        return new IndexedQuestion(key, vector, expiresAt);
    }

    @Test
    void aQuestionReadsBackWithItsVectorAnswerKeyAndExpiry() {
        String group = group();
        Instant expiresAt = now.plus(Duration.ofHours(24)).truncatedTo(ChronoUnit.MILLIS);
        float[] vector = {0.25f, -1.5f, 3.0e-7f, Float.MIN_VALUE};

        index(32).add(group, question("a".repeat(64), expiresAt, vector));

        IndexedQuestion read = index(32).questions(group).getFirst();
        assertThat(read.getAnswerKey()).isEqualTo("a".repeat(64));
        assertThat(read.getVector()).containsExactly(vector);
        assertThat(read.getExpiresAt()).isEqualTo(expiresAt);
    }

    @Test
    void groupsDoNotSeeEachOther() {
        String a = group();
        index(32).add(a, question("k1", now.plusSeconds(60), 1f));

        assertThat(index(32).questions(group())).isEmpty();
        assertThat(index(32).questions(a)).hasSize(1);
    }

    @Test
    void expiredQuestionsAreNotReturnedAndAreDroppedOnTheNextAdd() {
        String group = group();
        RedisSimilarQuestionIndex index = index(32);
        index.add(group, question("old", now.minusSeconds(1), 1f));
        index.add(group, question("live", now.plusSeconds(60), 1f));

        assertThat(index.questions(group)).extracting(IndexedQuestion::getAnswerKey).containsExactly("live");
        assertThat(redis.opsForZSet().size(RedisSimilarQuestionIndex.redisKey(group))).isEqualTo(1);
    }

    @Test
    void aGroupKeepsItsLongestLivedQuestionsUpToTheBound() {
        String group = group();
        RedisSimilarQuestionIndex index = index(2);
        index.add(group, question("k1", now.plusSeconds(10), 1f));
        index.add(group, question("k3", now.plusSeconds(30), 1f));
        index.add(group, question("k2", now.plusSeconds(20), 1f));

        assertThat(index.questions(group)).extracting(IndexedQuestion::getAnswerKey).containsExactlyInAnyOrder("k2", "k3");
    }

    @Test
    void theGroupExpiresWithItsLastAnswer() {
        String group = group();
        index(32).add(group, question("k1", now.plusSeconds(600), 1f));
        index(32).add(group, question("k2", now.plusSeconds(60), 1f));

        Long ttl = redis.getExpire(RedisSimilarQuestionIndex.redisKey(group));
        assertThat(ttl).as("the longest-lived answer, not the last one added").isBetween(120L, 600L);
    }

    @Test
    void theSameAnswerKeyAddedAgainIsOneEntry() {
        String group = group();
        index(32).add(group, question("k", now.plusSeconds(60), 1f, 0f));
        index(32).add(group, question("k", now.plusSeconds(120), 1f, 0f));

        assertThat(index(32).questions(group)).singleElement()
            .extracting(IndexedQuestion::getExpiresAt).isEqualTo(now.plusSeconds(120).truncatedTo(ChronoUnit.MILLIS));
    }
}
