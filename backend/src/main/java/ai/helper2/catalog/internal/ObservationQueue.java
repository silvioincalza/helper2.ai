package ai.helper2.catalog.internal;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes what searches observed off the request path: one background thread and a bounded queue. One
 * writer keeps upserts of the same rows from deadlocking each other; when the queue is full the newest
 * write is dropped and counted (helper2.catalog.observations{outcome=dropped}), never waited for.
 * A dropped write only delays a discovery date until the resource is seen again.
 */
public class ObservationQueue implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ObservationQueue.class);

    private final ThreadPoolExecutor executor;
    private final Counter written;
    private final Counter dropped;
    private final Counter failed;

    public ObservationQueue(int capacity, MeterRegistry registry) {
        this.executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(capacity), r -> {
            Thread t = new Thread(r, "catalog-observations");
            t.setDaemon(true);
            return t;
        }, new ThreadPoolExecutor.AbortPolicy());
        this.written = counter(registry, "written");
        this.dropped = counter(registry, "dropped");
        this.failed = counter(registry, "failed");
        registry.gauge("helper2.catalog.observations.queued", executor, e -> e.getQueue().size());
    }

    private static Counter counter(MeterRegistry registry, String outcome) {
        return Counter.builder("helper2.catalog.observations").description("Background catalog writes of search results")
            .tag("outcome", outcome).register(registry);
    }

    /** Queues [write]; returns false (and counts a drop) when the queue is full or shut down. */
    public boolean offer(Runnable write) {
        try {
            executor.execute(() -> run(write));
            return true;
        } catch (RejectedExecutionException e) {
            dropped.increment();
            return false;
        }
    }

    private void run(Runnable write) {
        try {
            write.run();
            written.increment();
        } catch (RuntimeException e) {
            failed.increment();
            log.warn("Recording search results failed: {}", e.getClass().getSimpleName());
        }
    }

    /** Finishes queued writes for a few seconds on shutdown, then gives up on the rest. */
    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
