package ai.searchavo.admin.web;

import ai.searchavo.platform.ApiProblem;
import ai.searchavo.platform.NotFoundException;
import ai.searchavo.platform.ProblemType;
import ai.searchavo.research.ResearchRun;
import ai.searchavo.research.ResearchStep;
import ai.searchavo.research.config.ResearchProperties;
import ai.searchavo.research.usecase.FollowResearchRun;
import ai.searchavo.research.usecase.FollowResearchRun.Progress;
import ai.searchavo.research.usecase.FollowResearchRun.Seen;
import java.io.IOException;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1")
class ReviewResearchEventsController {

  static final Duration POLL = Duration.ofSeconds(1);
  static final Duration HEARTBEAT = Duration.ofSeconds(15);
  static final Duration TIMEOUT = Duration.ofMinutes(10);

  private final FollowResearchRun follow;
  private final Semaphore streams;
  private final Duration poll;
  private final Duration heartbeat;
  private final Supplier<SseEmitter> emitters;

  @Autowired
  ReviewResearchEventsController(FollowResearchRun follow, ResearchProperties research) {
    this(follow, research.maxStreams(), POLL, HEARTBEAT, () -> new SseEmitter(TIMEOUT.toMillis()));
  }

  ReviewResearchEventsController(
      FollowResearchRun follow,
      int maxStreams,
      Duration poll,
      Duration heartbeat,
      Supplier<SseEmitter> emitters) {
    this.follow = follow;
    this.streams = new Semaphore(maxStreams);
    this.poll = poll;
    this.heartbeat = heartbeat;
    this.emitters = emitters;
  }

  @GetMapping(
      path = "/admin/review-research/{runId}/events",
      produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  ResponseEntity<SseEmitter> streamReviewResearch(
      @PathVariable UUID runId, @RequestParam(defaultValue = "0") int afterSeq) {
    ResearchRun first =
        follow.run(runId).orElseThrow(() -> new NotFoundException("Research run not found."));
    if (!streams.tryAcquire()) {
      throw new ApiProblem(
          ProblemType.RATE_LIMITED, "Too many research streams are open. Close one and try again.");
    }
    Stream stream = new Stream(first.id(), Math.max(0, afterSeq));
    try {
      stream.open();
    } catch (RuntimeException e) {
      stream.release();
      throw e;
    }
    return ResponseEntity.ok()
        .header("X-Accel-Buffering", "no")
        .header("Cache-Control", "no-store")
        .body(stream.emitter);
  }

  private final class Stream {

    private final UUID runId;
    private final SseEmitter emitter = emitters.get();
    private final AtomicBoolean released = new AtomicBoolean();
    private volatile boolean stopped;
    private Seen seen;
    private long lastSend = System.nanoTime();

    Stream(UUID runId, int afterSeq) {
      this.runId = runId;
      this.seen = Seen.after(afterSeq);
    }

    void open() {
      emitter.onCompletion(this::stop);
      emitter.onTimeout(this::stop);
      emitter.onError(e -> stop());
      Thread.ofVirtual().name("research-events").start(this::run);
    }

    void stop() {
      stopped = true;
    }

    void release() {
      if (released.compareAndSet(false, true)) {
        streams.release();
      }
    }

    private void run() {
      try {
        stream();
        emitter.complete();
      } catch (IOException | RuntimeException e) {
        emitter.completeWithError(e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        emitter.complete();
      } finally {
        release();
      }
    }

    private void stream() throws IOException, InterruptedException {
      long deadline = System.nanoTime() + TIMEOUT.toNanos();
      while (!stopped && System.nanoTime() < deadline) {
        Optional<Progress> next = follow.next(runId, seen);
        if (next.isEmpty()) {
          return;
        }
        Progress progress = next.get();
        sendStatus(progress);
        sendSteps(progress);
        seen = progress.seen();
        if (progress.ended()) {
          return;
        }
        sendHeartbeatWhenQuiet();
        Thread.sleep(poll);
      }
    }

    private void sendStatus(Progress progress) throws IOException {
      if (progress.statusChanged()) {
        send(
            SseEmitter.event()
                .name("status")
                .data(ReviewResearchController.runDto(progress.run()), MediaType.APPLICATION_JSON));
      }
    }

    private void sendSteps(Progress progress) throws IOException {
      for (ResearchStep s : progress.steps()) {
        send(
            SseEmitter.event()
                .name("step")
                .data(ReviewResearchController.stepDto(s), MediaType.APPLICATION_JSON));
      }
    }

    private void sendHeartbeatWhenQuiet() throws IOException {
      if (System.nanoTime() - lastSend >= heartbeat.toNanos()) {
        send(SseEmitter.event().comment("keep-alive"));
      }
    }

    private void send(SseEmitter.SseEventBuilder event) throws IOException {
      emitter.send(event);
      lastSend = System.nanoTime();
    }
  }
}
