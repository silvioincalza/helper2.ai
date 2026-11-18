package ai.helper2.research.usecase;

import ai.helper2.research.ResearchRun;
import ai.helper2.research.ResearchRuns;
import ai.helper2.research.ResearchStep;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public final class FollowResearchRun {

  private final ResearchRuns runs;

  public FollowResearchRun(ResearchRuns runs) {
    this.runs = runs;
  }

  public record Seen(int seq, ResearchRun.@Nullable Status status, @Nullable Instant startedAt) {

    public static Seen after(int seq) {
      return new Seen(seq, null, null);
    }
  }

  public record Progress(
      ResearchRun run, boolean statusChanged, List<ResearchStep> steps, boolean ended, Seen seen) {}

  public Optional<ResearchRun> run(UUID runId) {
    return runs.find(runId);
  }

  public List<ResearchStep> steps(UUID runId, int afterSeq) {
    return runs.steps(runId, afterSeq);
  }

  public Optional<Progress> next(UUID runId, Seen seen) {
    return runs.find(runId).map(run -> progress(runId, run, seen));
  }

  private Progress progress(UUID runId, ResearchRun run, Seen seen) {
    boolean startedAgain = startedAgain(run, seen);
    int from = run.status() == ResearchRun.Status.QUEUED || startedAgain ? 0 : seen.seq();
    List<ResearchStep> steps = runs.steps(runId, from);
    int last = steps.isEmpty() ? from : steps.getLast().seq();
    return new Progress(
        run,
        run.status() != seen.status() || startedAgain,
        steps,
        ended(runId, run, last),
        new Seen(last, run.status(), run.startedAt()));
  }

  private static boolean startedAgain(ResearchRun run, Seen seen) {
    return seen.startedAt() != null
        && run.startedAt() != null
        && !seen.startedAt().equals(run.startedAt());
  }

  private boolean ended(UUID runId, ResearchRun run, int lastSeq) {
    return !run.status().active() && runs.steps(runId, lastSeq).isEmpty();
  }
}
