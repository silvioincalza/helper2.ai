package ai.helper2.admin.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.scout.RunStatus;
import ai.helper2.scout.RunSummary;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GetOverviewTest {

    static final Instant SINCE = Instant.parse("2026-11-03T05:00:00Z");

    static RunSummary run(RunStatus status, Instant startedAt) {
        return new RunSummary(UUID.randomUUID(), "github", status, startedAt, null, 0, 0, 0, null);
    }

    @Test
    void countsOnlyFailedRunsStartedInTheLastDay() {
        List<RunSummary> runs = List.of(
            run(RunStatus.FAILED, SINCE.plusSeconds(60)),
            run(RunStatus.FAILED, SINCE),
            run(RunStatus.SUCCEEDED, SINCE.plusSeconds(60)),
            run(RunStatus.FAILED, SINCE.minusSeconds(1)));
        assertThat(GetOverview.failedSince(runs, SINCE)).isEqualTo(2);
    }
}
