package ai.helper2.platform.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.helper2.TestcontainersConfiguration;
import ai.helper2.platform.quota.QuotaCounter.Counter;
import ai.helper2.platform.quota.QuotaCounter.Verdict;
import ai.helper2.platform.resilience.Resilience;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/** The daily quota counters on the real Redis: check and count in one step, per field, with a 24 h expiry. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RedisQuotaCounterIT {

    @Autowired
    StringRedisTemplate redis;

    private RedisQuotaCounter counter() {
        return new RedisQuotaCounter(redis, Resilience.NONE);
    }

    private static String key() {
        return "test:quota:" + UUID.randomUUID();
    }

    @Test
    void countsUpToTheLimitThenRefusesWithoutCounting() {
        Counter own = new Counter(key(), "search");

        assertThat(counter().take(own, 2, null, 0)).isEqualTo(new QuotaCounter.Result(Verdict.TAKEN, 1));
        assertThat(counter().take(own, 2, null, 0)).isEqualTo(new QuotaCounter.Result(Verdict.TAKEN, 2));
        assertThat(counter().take(own, 2, null, 0)).isEqualTo(new QuotaCounter.Result(Verdict.LIMIT_REACHED, 2));
        assertThat(counter().used(own)).isEqualTo(2);
    }

    @Test
    void eachFieldOfAKeyCountsOnItsOwn() {
        String key = key();

        counter().take(new Counter(key, "search"), 3, null, 0);
        counter().take(new Counter(key, "search"), 3, null, 0);

        assertThat(counter().take(new Counter(key, "guide"), 3, null, 0).used()).isEqualTo(1);
        assertThat(counter().used(new Counter(key, "search"))).isEqualTo(2);
        assertThat(counter().used(new Counter(key(), "search"))).as("a key nobody counted").isZero();
    }

    @Test
    void theGlobalLimitRefusesEvenAnUnusedOwnCounterAndCountsOnlyWhatPasses() {
        Counter global = new Counter(key(), "tavily");
        Counter a = new Counter(key(), "search");
        Counter b = new Counter(key(), "search");

        assertThat(counter().take(a, 3, global, 2).verdict()).isEqualTo(Verdict.TAKEN);
        assertThat(counter().take(null, 0, global, 2).verdict()).as("an action already counted: global only").isEqualTo(Verdict.TAKEN);
        assertThat(counter().take(b, 3, global, 2)).isEqualTo(new QuotaCounter.Result(Verdict.GLOBAL_LIMIT_REACHED, 0));
        assertThat(counter().used(b)).isZero();
        assertThat(counter().used(global)).isEqualTo(2);
    }

    @Test
    void givingBackTakesOneCountAwayButNeverGoesBelowZero() {
        Counter global = new Counter(key(), "credits");
        counter().take(null, 0, global, Long.MAX_VALUE);
        counter().take(null, 0, global, Long.MAX_VALUE);

        counter().giveBack(global);
        assertThat(counter().used(global)).isEqualTo(1);
        counter().giveBack(global);
        counter().giveBack(global);
        assertThat(counter().used(global)).isZero();
        counter().giveBack(new Counter(key(), "credits"));
        assertThat(counter().used(new Counter(key(), "credits"))).as("a key nobody counted").isZero();
    }

    @Test
    void aGlobalCounterWithoutACapCountsEveryCallAndRefusesNone() {
        Counter global = new Counter(key(), "credits");

        for (int i = 0; i < 3; i++) {
            assertThat(counter().take(null, 0, global, Long.MAX_VALUE).verdict()).isEqualTo(Verdict.TAKEN);
        }
        assertThat(counter().used(global)).isEqualTo(3);
    }

    @Test
    void aCounterExpiresOneDayAfterItsFirstCount() {
        Counter own = new Counter(key(), "guide");
        Counter global = new Counter(key(), "tavily");

        counter().take(own, 3, global, 100);
        counter().take(own, 3, global, 100);

        for (String k : List.of(own.key(), global.key())) {
            assertThat(redis.getExpire(k, TimeUnit.SECONDS)).isBetween(Duration.ofDays(1).minusMinutes(1).toSeconds(), Duration.ofDays(1).toSeconds());
        }
    }

    @Test
    void concurrentTakesNeverPassTheLimit() throws Exception {
        Counter own = new Counter(key(), "search");
        Counter global = new Counter(key(), "tavily");
        AtomicInteger taken = new AtomicInteger();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> calls = new java.util.ArrayList<>();
            for (int i = 0; i < 50; i++) {
                calls.add(pool.submit(() -> {
                    if (counter().take(own, 10, global, 7).verdict() == Verdict.TAKEN) {
                        taken.incrementAndGet();
                    }
                }));
            }
            for (Future<?> f : calls) {
                f.get();
            }
        }

        assertThat(taken).hasValue(7);
        assertThat(counter().used(own)).isEqualTo(7);
        assertThat(counter().used(global)).isEqualTo(7);
    }

    @Test
    void deleteRemovesTheKeys() {
        Counter own = new Counter(key(), "search");
        counter().take(own, 3, null, 0);

        counter().delete(List.of(own.key(), key()));

        assertThat(redis.hasKey(own.key())).isFalse();
    }

    @Test
    void anUnreachableRedisIsAnErrorNotAnAnswer() {
        StringRedisTemplate down = new StringRedisTemplate() {
            @Override
            public <T> T execute(RedisScript<T> script, List<String> keys, Object... args) {
                throw new RedisConnectionFailureException("down");
            }
        };

        assertThatThrownBy(() -> new RedisQuotaCounter(down, Resilience.NONE).take(new Counter(key(), "search"), 3, null, 0))
            .isInstanceOf(RedisConnectionFailureException.class);
    }
}
