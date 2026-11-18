package ai.helper2.admin.web;

import ai.helper2.platform.NotFoundException;
import ai.helper2.research.ResearchRun;
import ai.helper2.research.ResearchRuns;
import ai.helper2.research.ResearchStep;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
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
  static final Duration TIMEOUT = Duration.ofMinutes(10);

  private final ResearchRuns runs;

  ReviewResearchEventsController(ResearchRuns runs) {
    this.runs = runs;
  }

  @GetMapping(
      path = "/admin/review-research/{runId}/events",
      produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  ResponseEntity<SseEmitter> streamReviewResearch(
      @PathVariable UUID runId, @RequestParam(defaultValue = "0") int afterSeq) {
    ResearchRun first =
        runs.find(runId).orElseThrow(() -> new NotFoundException("Research run not found."));
    SseEmitter emitter = new SseEmitter(TIMEOUT.toMillis());
    Thread.ofVirtual()
        .name("research-events")
        .start(() -> stream(emitter, first, Math.max(0, afterSeq)));
    return ResponseEntity.ok()
        .header("X-Accel-Buffering", "no")
        .header("Cache-Control", "no-store")
        .body(emitter);
  }

  private void stream(SseEmitter emitter, ResearchRun first, int afterSeq) {
    long deadline = System.nanoTime() + TIMEOUT.toNanos();
    int seq = afterSeq;
    ResearchRun.Status sent = null;
    try {
      while (System.nanoTime() < deadline) {
        List<ResearchStep> steps = runs.steps(first.id(), seq);
        for (ResearchStep s : steps) {
          emitter.send(
              SseEmitter.event()
                  .name("step")
                  .data(ReviewResearchController.stepDto(s), MediaType.APPLICATION_JSON));
          seq = s.seq();
        }
        ResearchRun run = runs.find(first.id()).orElse(null);
        if (run == null) {
          break;
        }
        if (run.status() != sent) {
          emitter.send(
              SseEmitter.event()
                  .name("status")
                  .data(ReviewResearchController.runDto(run), MediaType.APPLICATION_JSON));
          sent = run.status();
        }
        if (!run.status().active() && runs.steps(first.id(), seq).isEmpty()) {
          break;
        }
        Thread.sleep(POLL);
      }
      emitter.complete();
    } catch (IOException | IllegalStateException e) {
      emitter.completeWithError(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      emitter.complete();
    }
  }
}
