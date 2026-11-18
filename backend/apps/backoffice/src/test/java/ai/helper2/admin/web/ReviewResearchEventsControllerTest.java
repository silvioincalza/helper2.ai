package ai.helper2.admin.web;

import static ai.helper2.testing.Eventually.eventually;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.helper2.platform.ApiProblem;
import ai.helper2.research.ResearchFilter;
import ai.helper2.research.ResearchResult;
import ai.helper2.research.ResearchRun;
import ai.helper2.research.ResearchRuns;
import ai.helper2.research.ResearchStep;
import ai.helper2.research.usecase.FollowResearchRun;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class ReviewResearchEventsControllerTest {

  final UUID runId = UUID.randomUUID();
  final Runs runs = new Runs();
  final List<Recording> emitters = new CopyOnWriteArrayList<>();

  ReviewResearchEventsController controller(int maxStreams) {
    return new ReviewResearchEventsController(
        new FollowResearchRun(runs),
        maxStreams,
        Duration.ofMillis(5),
        Duration.ofMillis(20),
        () -> {
          Recording r = new Recording();
          emitters.add(r);
          return r;
        });
  }

  Optional<SseEmitter> aNewStream(ReviewResearchEventsController controller) {
    try {
      return Optional.ofNullable(controller.streamReviewResearch(runId, 0).getBody());
    } catch (ApiProblem refused) {
      return Optional.empty();
    }
  }

  @Test
  void aQuietRunGetsAHeartbeatComment() throws Exception {
    controller(2).streamReviewResearch(runId, 0);

    eventually(() -> assertThat(emitters.getFirst().comments.get()).isPositive());
    emitters.getFirst().complete();
  }

  @Test
  void streamsBeyondTheCapAreRefusedUntilOneEnds() throws Exception {
    ReviewResearchEventsController controller = controller(1);
    controller.streamReviewResearch(runId, 0);

    assertThatThrownBy(() -> controller.streamReviewResearch(runId, 0))
        .isInstanceOf(ApiProblem.class);

    emitters.getFirst().complete();
    eventually(() -> assertThat(aNewStream(controller)).isPresent());
    emitters.getLast().complete();
  }

  @Test
  void aBrowserThatLeftStopsThePolling() throws Exception {
    controller(1).streamReviewResearch(runId, 0);
    eventually(() -> assertThat(runs.finds.get()).isGreaterThan(1));

    emitters.getFirst().complete();
    Thread.sleep(100);
    int after = runs.finds.get();
    Thread.sleep(100);

    assertThat(runs.finds.get()).isEqualTo(after);
  }

  @Test
  void aFinishedRunEndsTheStreamAndFreesItsPlace() throws Exception {
    runs.status = ResearchRun.Status.DONE;
    ReviewResearchEventsController controller = controller(1);
    controller.streamReviewResearch(runId, 0);

    eventually(() -> assertThat(emitters.getFirst().events.get()).isEqualTo(1));
    eventually(() -> assertThat(aNewStream(controller)).isPresent());
  }

  static ResearchStep step(UUID runId, int seq) {
    return new ResearchStep(
        runId,
        seq,
        ResearchStep.Kind.MODEL_CALL,
        "m",
        "q",
        "a",
        null,
        1,
        null,
        null,
        Instant.now());
  }

  @Test
  void aRunPutBackInTheQueueStreamsItsNewStepsFromTheStart() throws Exception {
    runs.stepList = List.of(step(runId, 1), step(runId, 2));
    controller(1).streamReviewResearch(runId, 0);
    eventually(() -> assertThat(emitters.getFirst().events.get()).isEqualTo(3));

    runs.stepList = List.of();
    runs.startedAt = Instant.parse("2026-11-19T00:00:01Z");
    runs.status = ResearchRun.Status.QUEUED;
    eventually(() -> assertThat(emitters.getFirst().events.get()).isEqualTo(4));
    runs.status = ResearchRun.Status.RUNNING;
    runs.stepList = List.of(step(runId, 1));

    eventually(() -> assertThat(emitters.getFirst().events.get()).isEqualTo(6));
    emitters.getFirst().complete();
  }

  @Test
  void aRunClaimedAgainBeforeTheStreamSawItQueuedStreamsItsNewSteps() throws Exception {
    runs.stepList = List.of(step(runId, 1), step(runId, 2));
    controller(1).streamReviewResearch(runId, 0);
    eventually(() -> assertThat(emitters.getFirst().events.get()).isEqualTo(3));

    runs.stepList = List.of(step(runId, 1));
    runs.startedAt = Instant.parse("2026-11-19T00:00:01Z");

    eventually(() -> assertThat(emitters.getFirst().events.get()).isEqualTo(5));
    assertThat(emitters.getFirst().names)
        .containsExactly("status", "step", "step", "status", "step");
    emitters.getFirst().complete();
  }

  static final class Recording extends SseEmitter {
    final AtomicInteger events = new AtomicInteger();
    final AtomicInteger comments = new AtomicInteger();
    final List<String> names = new CopyOnWriteArrayList<>();
    volatile boolean done;

    @Override
    public void send(SseEventBuilder event) throws IOException {
      if (done) {
        throw new IllegalStateException("completed");
      }
      boolean comment =
          event.build().stream().anyMatch(d -> String.valueOf(d.getData()).startsWith(":"));
      (comment ? comments : events).incrementAndGet();
      if (!comment) {
        String head = String.valueOf(event.build().iterator().next().getData());
        names.add(head.substring(head.indexOf(':') + 1, head.indexOf('\n')));
      }
    }

    @Override
    public void complete() {
      done = true;
    }
  }

  final class Runs implements ResearchRuns {
    volatile ResearchRun.Status status = ResearchRun.Status.RUNNING;
    volatile List<ResearchStep> stepList = List.of();
    volatile Instant startedAt = Instant.parse("2026-11-18T10:00:00Z");
    final AtomicInteger finds = new AtomicInteger();

    @Override
    public Optional<ResearchRun> find(UUID id) {
      finds.incrementAndGet();
      return Optional.of(
          new ResearchRun(
              runId,
              UUID.randomUUID(),
              status,
              "m",
              "admin",
              Instant.now(),
              status == ResearchRun.Status.QUEUED ? null : startedAt,
              null,
              0,
              0,
              0,
              null,
              null));
    }

    @Override
    public List<ResearchStep> steps(UUID id, int afterSeq) {
      return stepList.stream().filter(st -> st.seq() > afterSeq).toList();
    }

    @Override
    public List<ResearchRun> enqueue(Collection<UUID> itemIds, String model, String by) {
      return List.of();
    }

    @Override
    public Set<UUID> activeItems() {
      return Set.of();
    }

    @Override
    public Optional<ResearchRun> claimNext(Instant now) {
      return Optional.empty();
    }

    @Override
    public boolean requeue(UUID id) {
      return false;
    }

    @Override
    public void step(ResearchStep step) {}

    @Override
    public void done(UUID id, ResearchResult result, int p, int c, Instant at) {}

    @Override
    public void failed(UUID id, String error, int p, int c, Instant at) {}

    @Override
    public boolean cancel(UUID id, Instant at) {
      return false;
    }

    @Override
    public boolean isCancelled(UUID id) {
      return false;
    }

    @Override
    public int failStale(Instant before, Instant at) {
      return 0;
    }

    @Override
    public List<ResearchRun> latest(Collection<UUID> itemIds) {
      return List.of();
    }

    @Override
    public Set<UUID> itemsWhere(ResearchFilter filter) {
      return Set.of();
    }

    @Override
    public Set<UUID> doneItems() {
      return Set.of();
    }

    @Override
    public int purge(Instant before) {
      return 0;
    }
  }
}
