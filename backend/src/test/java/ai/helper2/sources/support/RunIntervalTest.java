package ai.helper2.sources.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class RunIntervalTest {

    final Instant now = Instant.parse("2026-10-22T10:00:00Z");
    final RunInterval fiveDays = new RunInterval(Duration.ofDays(5));

    @Test
    void firstRunIsAlwaysDue() {
        assertThat(fiveDays.isDue(null, now)).isTrue();
    }

    @Test
    void dueOnlyOnceTheIntervalHasPassedSinceTheLastCompletePass() {
        assertThat(fiveDays.isDue("2026-10-18T10:00:00Z", now)).isFalse();
        assertThat(fiveDays.isDue("2026-10-17T10:00:00Z", now)).isTrue();
        assertThat(fiveDays.isDue("2026-10-16T09:00:00Z", now)).isTrue();
    }

    @Test
    void anUnreadableCheckpointDoesNotBlockCollectionForever() {
        assertThat(fiveDays.isDue("not-a-date", now)).isTrue();
    }

    @Test
    void zeroMeansEveryScheduledRun() {
        assertThat(new RunInterval(Duration.ZERO).isDue("2026-10-22T09:59:00Z", now)).isTrue();
    }

    @Test
    void negativeIntervalIsRejected() {
        assertThatThrownBy(() -> new RunInterval(Duration.ofDays(-1))).isInstanceOf(IllegalArgumentException.class);
    }
}
