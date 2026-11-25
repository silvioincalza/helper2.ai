package ai.searchavo.research.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import ai.searchavo.research.ResearchRun;
import ai.searchavo.research.ResearchRuns;
import ai.searchavo.research.ResearchStep;
import ai.searchavo.research.usecase.FollowResearchRun.Progress;
import ai.searchavo.research.usecase.FollowResearchRun.Seen;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FollowResearchRunTest {

  static final Instant FIRST_START = Instant.parse("2026-11-18T10:00:00Z");
  static final Instant SECOND_START = Instant.parse("2026-11-18T10:05:00Z");

  final UUID runId = UUID.randomUUID();
  final List<ResearchStep> stored = new ArrayList<>();
  ResearchRun run = run(ResearchRun.Status.RUNNING, FIRST_START);
  boolean stepLandsAfterTheRead;

  final FollowResearchRun follow = new FollowResearchRun(runs());

  @Test
  void aRunThatIsNotThereHasNoProgress() {
    run = null;

    assertThat(follow.next(runId, Seen.after(0))).isEmpty();
    assertThat(follow.run(runId)).isEmpty();
  }

  @Test
  void theFirstLookTellsTheStatusAndTheStepsAfterTheSequenceNumber() {
    stored.addAll(List.of(step(1), step(2), step(3)));

    Progress progress = follow.next(runId, Seen.after(1)).orElseThrow();

    assertThat(progress.statusChanged()).isTrue();
    assertThat(progress.steps()).extracting(ResearchStep::seq).containsExactly(2, 3);
    assertThat(progress.ended()).isFalse();
    assertThat(progress.seen()).isEqualTo(new Seen(3, ResearchRun.Status.RUNNING, FIRST_START));
  }

  @Test
  void aSecondLookWithNothingNewTellsNoStatusAndNoStep() {
    stored.add(step(1));
    Seen seen = follow.next(runId, Seen.after(0)).orElseThrow().seen();

    Progress progress = follow.next(runId, seen).orElseThrow();

    assertThat(progress.statusChanged()).isFalse();
    assertThat(progress.steps()).isEmpty();
    assertThat(progress.seen()).isEqualTo(seen);
  }

  @Test
  void aQueuedRunIsReadFromItsFirstStep() {
    Seen seen = new Seen(2, ResearchRun.Status.RUNNING, FIRST_START);
    run = run(ResearchRun.Status.QUEUED, null);

    Progress progress = follow.next(runId, seen).orElseThrow();

    assertThat(progress.statusChanged()).isTrue();
    assertThat(progress.seen()).isEqualTo(new Seen(0, ResearchRun.Status.QUEUED, null));
  }

  @Test
  void aRunStartedAgainTellsItsStatusAgainAndItsStepsFromTheFirst() {
    Seen seen = new Seen(2, ResearchRun.Status.RUNNING, FIRST_START);
    run = run(ResearchRun.Status.RUNNING, SECOND_START);
    stored.add(step(1));

    Progress progress = follow.next(runId, seen).orElseThrow();

    assertThat(progress.statusChanged()).isTrue();
    assertThat(progress.steps()).extracting(ResearchStep::seq).containsExactly(1);
    assertThat(progress.seen()).isEqualTo(new Seen(1, ResearchRun.Status.RUNNING, SECOND_START));
  }

  @Test
  void aFinishedRunWithEveryStepReadHasEnded() {
    run = run(ResearchRun.Status.DONE, FIRST_START);
    stored.add(step(1));

    assertThat(follow.next(runId, Seen.after(0)).orElseThrow().ended()).isTrue();
  }

  @Test
  void aFinishedRunWithAStepStillToReadHasNotEnded() {
    run = run(ResearchRun.Status.DONE, FIRST_START);
    stored.add(step(1));
    stepLandsAfterTheRead = true;

    Progress progress = follow.next(runId, Seen.after(0)).orElseThrow();

    assertThat(progress.steps()).extracting(ResearchStep::seq).containsExactly(1);
    assertThat(progress.ended()).isFalse();
  }

  @Test
  void theStepsOfARunAreReadAfterASequenceNumber() {
    stored.addAll(List.of(step(1), step(2)));

    assertThat(follow.steps(runId, 1)).extracting(ResearchStep::seq).containsExactly(2);
    assertThat(follow.run(runId)).contains(run);
  }

  private ResearchRuns runs() {
    return (ResearchRuns)
        Proxy.newProxyInstance(
            ResearchRuns.class.getClassLoader(),
            new Class<?>[] {ResearchRuns.class},
            (proxy, method, args) ->
                switch (method.getName()) {
                  case "find" -> Optional.ofNullable(run);
                  case "steps" -> stepsAfter((int) args[1]);
                  default -> throw new UnsupportedOperationException(method.getName());
                });
  }

  private List<ResearchStep> stepsAfter(int afterSeq) {
    List<ResearchStep> found = stored.stream().filter(s -> s.seq() > afterSeq).toList();
    if (stepLandsAfterTheRead) {
      stepLandsAfterTheRead = false;
      stored.add(step(stored.size() + 1));
    }
    return found;
  }

  private ResearchRun run(ResearchRun.Status status, Instant startedAt) {
    return new ResearchRun(
        runId,
        UUID.randomUUID(),
        status,
        "m",
        "admin",
        FIRST_START,
        startedAt,
        null,
        0,
        0,
        0,
        null,
        null);
  }

  private ResearchStep step(int seq) {
    return new ResearchStep(
        runId, seq, ResearchStep.Kind.MODEL_CALL, "m", "q", "a", null, 1, null, null, FIRST_START);
  }
}
