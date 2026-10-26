package ai.helper2.platform;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.testing.MovableClock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class InMemoryRateLimiterTest {

    private static final Instant T0 = Instant.parse("2026-10-26T10:00:00Z");

    private final MovableClock clock = new MovableClock(T0);
    private final InMemoryRateLimiter limiter = new InMemoryRateLimiter(3, Duration.ofMinutes(1), clock);

    @Test
    void allowsUpToTheLimitThenReturnsSecondsToWait() {
        assertThat(limiter.acquire("1.2.3.4")).isZero();
        assertThat(limiter.acquire("1.2.3.4")).isZero();
        assertThat(limiter.acquire("1.2.3.4")).isZero();

        clock.now = T0.plusSeconds(20);
        assertThat(limiter.acquire("1.2.3.4")).isEqualTo(40);
    }

    @Test
    void windowResetsAfterItsDuration() {
        for (int i = 0; i < 4; i++) {
            limiter.acquire("1.2.3.4");
        }
        clock.now = T0.plusSeconds(60);
        assertThat(limiter.acquire("1.2.3.4")).isZero();
    }

    @Test
    void keysAreIndependent() {
        for (int i = 0; i < 4; i++) {
            limiter.acquire("1.2.3.4");
        }
        assertThat(limiter.acquire("5.6.7.8")).isZero();
    }

    @Test
    void expiredWindowsAreDroppedSoTheMapStaysSmall() {
        for (int i = 0; i < 100; i++) {
            limiter.acquire("10.0.0." + i);
        }
        assertThat(limiter.trackedClients()).isEqualTo(100);
        clock.now = T0.plusSeconds(61);
        limiter.acquire("1.2.3.4");
        assertThat(limiter.trackedClients()).isEqualTo(1);
    }

    @Test
    void aFullMapEvictsTheOldestWindow() {
        InMemoryRateLimiter small = new InMemoryRateLimiter(1, Duration.ofMinutes(1), clock, 2);
        small.acquire("1.1.1.1");
        clock.now = T0.plusSeconds(1);
        small.acquire("2.2.2.2");
        small.acquire("3.3.3.3");
        assertThat(small.trackedClients()).isEqualTo(2);
        // 1.1.1.1 was evicted, so it starts a new window (evicting 2.2.2.2); 3.3.3.3 is still limited.
        assertThat(small.acquire("1.1.1.1")).isZero();
        assertThat(small.acquire("3.3.3.3")).isPositive();
    }

    @Test
    void ipv6ClientsShareTheLimitOfTheirSlash64() {
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.acquire("2001:db8:1:2::" + Integer.toHexString(i + 1))).isZero();
        }
        assertThat(limiter.acquire("2001:db8:1:2:ffff:ffff:ffff:ffff")).isPositive();
        assertThat(limiter.acquire("2001:db8:1:3::1")).isZero();
    }

    @Test
    void neverAsksToWaitLessThanOneSecond() {
        for (int i = 0; i < 3; i++) {
            limiter.acquire("1.2.3.4");
        }
        clock.now = T0.plusMillis(59_900);
        assertThat(limiter.acquire("1.2.3.4")).isEqualTo(1);
    }
}
