package ai.helper2.research.usecase;

import ai.helper2.research.ResearchRuns;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

public final class FailStaleResearch {

  public static final Duration STALE = Duration.ofMinutes(5);

  private final ResearchRuns runs;
  private final Clock clock;

  public FailStaleResearch(ResearchRuns runs, Clock clock) {
    this.runs = runs;
    this.clock = clock;
  }

  public int failStale() {
    Instant now = clock.instant();
    return runs.failStale(now.minus(STALE), now);
  }
}
