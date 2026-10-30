package ai.helper2.guide.web;

import ai.helper2.platform.resilience.Resilience;
import io.micrometer.context.ContextExecutorService;
import io.micrometer.context.ContextSnapshotFactory;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The guide's two bounds, shared by the JSON and the streamed endpoint: a question runs only with a place in the
 * {@code guide} bulkhead ({@code H2_GUIDE_MAX_CONCURRENT}, ADR 0033), and each has a deadline
 * ({@code helper2.guide.question-deadline}) after which its worker is interrupted. Questions run on virtual threads
 * that carry the request's trace, so the guide spans stay under the HTTP request span.
 */
@Component
class GuideLimits implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(GuideLimits.class);

    /** What a client is told, by either endpoint, when every slot is taken (503) or the deadline passed (504). */
    static final String BUSY = "The guide is answering too many questions right now. Try again in a moment.";
    static final String TIMED_OUT = "The guide took too long to answer. Try again in a moment.";

    static final String POLICY = "guide";

    private final Resilience resilience;
    private final Duration deadline;
    private final ExecutorService executor = ContextExecutorService.wrap(Executors.newVirtualThreadPerTaskExecutor(),
        ContextSnapshotFactory.builder().build());

    GuideLimits(Resilience resilience, @Value("${helper2.guide.question-deadline:PT90S}") Duration deadline) {
        resilience.require(POLICY);
        this.resilience = resilience;
        this.deadline = deadline;
    }

    Duration deadline() {
        return deadline;
    }

    /**
     * Starts {@code work} on its own thread if a slot is free; false, with nothing started, when every slot is
     * taken or the application is shutting down. When the deadline comes first, {@code onTimeout} runs and then
     * the worker is interrupted. The slot is released exactly once: when the work ends or at the deadline,
     * whichever comes first.
     */
    boolean tryStart(Runnable work, Runnable onTimeout) {
        Optional<Resilience.Slot> slot = resilience.trySlot(POLICY);
        if (slot.isEmpty()) {
            log.warn("guide question refused: every slot is taken");
            return false;
        }
        Runnable release = slot.get()::release;
        Future<?> running;
        try {
            running = executor.submit(() -> {
                try {
                    work.run();
                } finally {
                    release.run();
                }
            });
        } catch (RejectedExecutionException e) {
            release.run();
            return false;
        }
        CompletableFuture.delayedExecutor(deadline.toMillis(), TimeUnit.MILLISECONDS).execute(() -> {
            if (running.isDone()) {
                return;
            }
            log.warn("guide question deadline of {} reached: cancelling it", deadline);
            // Before the interrupt, so the worker's own failure cannot reach the client first.
            onTimeout.run();
            running.cancel(true);
            // The slot is free from the deadline on, also for a worker that never started (no finally runs)
            // or that is slow to react to the interrupt (its own release is then a no-op).
            release.run();
        });
        return true;
    }

    @Override
    public void destroy() {
        executor.shutdownNow();
    }
}
