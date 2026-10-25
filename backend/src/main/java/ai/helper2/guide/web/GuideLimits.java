package ai.helper2.guide.web;

import io.micrometer.context.ContextExecutorService;
import io.micrometer.context.ContextSnapshotFactory;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The guide's two bounds, shared by the JSON and the streamed endpoint: at most {@code helper2.guide.max-concurrent}
 * questions run at once, and each has a deadline ({@code helper2.guide.question-deadline}) after which its worker
 * is interrupted. Questions run on virtual threads that carry the request's trace, so the guide spans stay under
 * the HTTP request span.
 */
@Component
class GuideLimits implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(GuideLimits.class);

    /** What a client is told, by either endpoint, when every slot is taken (503) or the deadline passed (504). */
    static final String BUSY = "The guide is answering too many questions right now. Try again in a moment.";
    static final String TIMED_OUT = "The guide took too long to answer. Try again in a moment.";

    private final int maxConcurrent;
    private final Semaphore permits;
    private final Duration deadline;
    private final ExecutorService executor = ContextExecutorService.wrap(Executors.newVirtualThreadPerTaskExecutor(),
        ContextSnapshotFactory.builder().build());

    GuideLimits(@Value("${helper2.guide.max-concurrent:8}") int maxConcurrent,
                @Value("${helper2.guide.question-deadline:PT90S}") Duration deadline) {
        this.maxConcurrent = maxConcurrent;
        this.permits = new Semaphore(maxConcurrent);
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
        if (!permits.tryAcquire()) {
            log.warn("guide question refused: {} questions already running", maxConcurrent);
            return false;
        }
        AtomicBoolean released = new AtomicBoolean();
        Runnable release = () -> {
            if (released.compareAndSet(false, true)) {
                permits.release();
            }
        };
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
