package ai.helper2.admin.web;

import ai.helper2.platform.ApiProblem;
import ai.helper2.platform.NotFoundException;
import ai.helper2.platform.ProblemType;
import ai.helper2.research.ResearchRun;
import ai.helper2.research.ResearchRuns;
import ai.helper2.research.ResearchStep;
import ai.helper2.research.config.ResearchProperties;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
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

  private final ResearchRuns runs;
  private final Semaphore streams;
  private final Duration poll;
  private final Duration heartbeat;
  private final Supplier<SseEmitter> emitters;

  @Autowired
  ReviewResearchEventsController(ResearchRuns runs, ResearchProperties research) {
    this(runs, research.maxStreams(), POLL, HEARTBEAT, () -> new SseEmitter(TIMEOUT.toMillis()));
  }

  ReviewResearchEventsController(
      ResearchRuns runs,
      int maxStreams,
      Duration poll,
      Duration heartbeat,
      Supplier<SseEmitter> emitters) {
    this.runs = runs;
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
        runs.find(runId).orElseThrow(() -> new NotFoundException("Research run not found."));
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
    private volatile Thread thread;
    private int seq;
    private ResearchRun.Status sent;
    private long lastSend = System.nanoTime();

    Stream(UUID runId, int afterSeq) {
      this.runId = runId;
      this.seq = afterSeq;
    }

    void open() {
      emitter.onCompletion(this::stop);
      emitter.onTimeout(this::stop);
      emitter.onError(e -> stop());
      thread = Thread.ofVirtual().name("research-events").start(this::run);
    }

    void stop() {
      stopped = true;
      Thread t = thread;
      if (t != null) {
        t.interrupt();
      }
    }

    void release() {
      if (released.compareAndSet(false, true)) {
        streams.release();
      }
    }

    private void run() {
      try {
        follow();
        emitter.complete();
      } catch (IOException | IllegalStateException e) {
        emitter.completeWithError(e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        emitter.complete();
      } finally {
        release();
      }
    }

    private void follow() throws IOException, InterruptedException {
      long deadline = System.nanoTime() + TIMEOUT.toNanos();
      while (!stopped && System.nanoTime() < deadline) {
        sendSteps();
        ResearchRun run = runs.find(runId).orElse(null);
        if (run == null) {
          return;
        }
        sendStatus(run);
        if (!run.status().active() && runs.steps(runId, seq).isEmpty()) {
          return;
        }
        sendHeartbeatWhenQuiet();
        Thread.sleep(poll);
      }
    }

    private void sendSteps() throws IOException {
      List<ResearchStep> steps = runs.steps(runId, seq);
      for (ResearchStep s : steps) {
        send(
            SseEmitter.event()
                .name("step")
                .data(ReviewResearchController.stepDto(s), MediaType.APPLICATION_JSON));
        seq = s.seq();
      }
    }

    private void sendStatus(ResearchRun run) throws IOException {
      if (run.status() != sent) {
        send(
            SseEmitter.event()
                .name("status")
                .data(ReviewResearchController.runDto(run), MediaType.APPLICATION_JSON));
        sent = run.status();
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
