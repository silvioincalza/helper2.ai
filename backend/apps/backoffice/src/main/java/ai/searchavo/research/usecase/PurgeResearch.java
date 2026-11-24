package ai.searchavo.research.usecase;

import ai.searchavo.research.ResearchRuns;
import java.time.Clock;
import java.time.Duration;

public final class PurgeResearch {

  public static final Duration KEEP = Duration.ofDays(90);

  private final ResearchRuns runs;
  private final Clock clock;

  public PurgeResearch(ResearchRuns runs, Clock clock) {
    this.runs = runs;
    this.clock = clock;
  }

  public int purge() {
    return runs.purge(clock.instant().minus(KEEP));
  }
}
