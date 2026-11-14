package ai.helper2.guide.web;

import ai.helper2.guide.QuestionDeadline;
import ai.helper2.guide.WorkGate;
import ai.helper2.guide.config.GuideProperties;
import ai.helper2.platform.resilience.Resilience;
import ai.helper2.shared.Presence;
import io.micrometer.context.ContextExecutorService;
import io.micrometer.context.ContextSnapshotFactory;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
class GuideLimits implements DisposableBean {

  private static final Logger log = LoggerFactory.getLogger(GuideLimits.class);

  static final String BUSY =
      "The guide is answering too many questions right now. Try again in a moment.";

  static final String TIMED_OUT = "The guide took too long to answer. Try again in a moment.";

  static final String POLICY = "guide";

  static final Duration ANSWER_GRACE = Duration.ofSeconds(3);

  static final Duration LEAVE_GRACE = Duration.ofSeconds(3);

  private final Resilience resilience;
  private final Duration deadline;
  private final Duration answerGrace;
  private final Duration leaveGrace;
  private final Clock clock;
  private final ContextSnapshotFactory snapshots = ContextSnapshotFactory.builder().build();

  private final ContextSnapshotFactory traceOnly =
      ContextSnapshotFactory.builder()
          .captureKeyPredicate(ObservationThreadLocalAccessor.KEY::equals)
          .build();

  private final ExecutorService executor =
      ContextExecutorService.wrap(Executors.newVirtualThreadPerTaskExecutor(), snapshots);

  private final int questionsPerClient;
  private final ConcurrentHashMap<String, Integer> runningByClient = new ConcurrentHashMap<>();

  @Autowired
  GuideLimits(Resilience resilience, GuideProperties settings, Clock clock) {
    this(
        resilience,
        settings.questionDeadline(),
        ANSWER_GRACE,
        clock,
        settings.fairness().clientConcurrentQuestions());
  }

  GuideLimits(Resilience resilience, Duration deadline) {
    this(resilience, deadline, ANSWER_GRACE, Clock.systemUTC(), DEFAULT_QUESTIONS_PER_CLIENT);
  }

  GuideLimits(Resilience resilience, Duration deadline, Clock clock, int questionsPerClient) {
    this(resilience, deadline, ANSWER_GRACE, clock, questionsPerClient);
  }

  GuideLimits(
      Resilience resilience,
      Duration deadline,
      Duration answerGrace,
      Clock clock,
      int questionsPerClient) {
    this(resilience, deadline, answerGrace, LEAVE_GRACE, clock, questionsPerClient);
  }

  GuideLimits(
      Resilience resilience,
      Duration deadline,
      Duration answerGrace,
      Duration leaveGrace,
      Clock clock,
      int questionsPerClient) {
    resilience.require(POLICY);
    this.resilience = resilience;
    this.deadline = deadline;
    this.answerGrace = answerGrace;
    this.leaveGrace = leaveGrace;
    this.clock = clock;
    this.questionsPerClient = questionsPerClient;
  }

  static final int DEFAULT_QUESTIONS_PER_CLIENT = 2;

  Duration lastAnswerBy() {
    return deadline.plus(answerGrace);
  }

  void whenTheAskerStaysAway(Presence asker) {
    after(leaveGrace, asker::leave);
  }

  boolean deadlineIsOver() {
    return QuestionDeadline.isOver(clock);
  }

  boolean tryStart(String client, Consumer<WorkGate> work, Runnable onNoAnswerInTime) {
    Optional<Runnable> turn = turnOf(client);
    if (turn.isEmpty()) {
      log.warn("guide question refused: the client has {} running", questionsPerClient);
      return false;
    }
    LazySlot slot = new LazySlot();
    Runnable done =
        () -> {
          slot.leave();
          turn.get().run();
        };
    Instant end = clock.instant().plus(deadline);
    CompletableFuture<Void> ended = new CompletableFuture<>();
    Future<?> running;
    try {
      running =
          executor.submit(() -> {
            try {
              QuestionDeadline.runUntil(end, () -> work.accept(slot));
            } finally {
              done.run();
              ended.complete(null);
            }
          });
    } catch (RejectedExecutionException e) {
      done.run();
      return false;
    }
    after(
        deadline,
        () -> {
          if (ended.isDone()) {
            return;
          }
          log.warn(
              "guide question deadline of {} reached: cancelling it, it has {} to answer with what it has",
              deadline,
              answerGrace);
          running.cancel(true);
          done.run();
          after(answerGrace, () -> giveUpOn(ended, onNoAnswerInTime));
        });
    return true;
  }

  private void after(Duration delay, Runnable task) {
    CompletableFuture.delayedExecutor(delay.toMillis(), TimeUnit.MILLISECONDS)
        .execute(traceOnly.captureAll().wrap(task));
  }

  private void giveUpOn(CompletableFuture<Void> ended, Runnable onNoAnswerInTime) {
    if (ended.isDone()) {
      return;
    }
    log.warn("guide question did not end {} after its deadline: no answer", answerGrace);
    onNoAnswerInTime.run();
  }

  private Optional<Runnable> turnOf(String client) {
    AtomicBoolean admitted = new AtomicBoolean();
    runningByClient.compute(
        client,
        (key, running) -> {
          int now = running == null ? 0 : running;
          if (now >= questionsPerClient) {
            return running;
          }
          admitted.set(true);
          return now + 1;
        });
    if (!admitted.get()) {
      return Optional.empty();
    }
    AtomicBoolean ended = new AtomicBoolean();
    return Optional.of(() -> {
      if (ended.compareAndSet(false, true)) {
        runningByClient.computeIfPresent(
            client, (key, running) -> running <= 1 ? null : running - 1);
      }
    });
  }

  private final class LazySlot implements WorkGate {
    private final AtomicReference<Resilience.Slot> held = new AtomicReference<>();

    @Override
    public boolean enter() {
      if (held.get() != null) {
        return true;
      }
      Optional<Resilience.Slot> slot = resilience.trySlot(POLICY);
      if (slot.isEmpty()) {
        log.warn("guide question refused: every slot is taken");
        return false;
      }
      if (!held.compareAndSet(null, slot.get())) {
        slot.get().release();
      }
      return true;
    }

    @Override
    public void leave() {
      Resilience.Slot slot = held.getAndSet(null);
      if (slot != null) {
        slot.release();
      }
    }
  }

  @Override
  public void destroy() {
    executor.shutdownNow();
  }
}
