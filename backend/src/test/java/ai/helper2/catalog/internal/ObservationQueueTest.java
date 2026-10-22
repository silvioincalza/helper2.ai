package ai.helper2.catalog.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ObservationQueueTest {

    private final MeterRegistry meters = new SimpleMeterRegistry();

    private double count(String outcome) {
        return meters.get("helper2.catalog.observations").tag("outcome", outcome).counter().count();
    }

    @Test
    void writesRunInTheBackgroundAndAreCounted() {
        AtomicInteger runs = new AtomicInteger();
        try (ObservationQueue queue = new ObservationQueue(4, meters)) {
            assertThat(queue.offer(runs::incrementAndGet)).isTrue();
            assertThat(queue.offer(() -> { throw new IllegalStateException("db down"); })).isTrue();
        }
        assertThat(runs).hasValue(1);
        assertThat(count("written")).isEqualTo(1);
        assertThat(count("failed")).isEqualTo(1);
        assertThat(count("dropped")).isZero();
    }

    @Test
    void aFullQueueDropsTheNewestWriteInsteadOfWaiting() throws InterruptedException {
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (ObservationQueue queue = new ObservationQueue(1, meters)) {
            queue.offer(() -> {
                running.countDown();
                await(release);
            });
            assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(queue.offer(() -> { })).isTrue();
            assertThat(meters.get("helper2.catalog.observations.queued").gauge().value()).isEqualTo(1);

            assertThat(queue.offer(() -> { })).isFalse();
            assertThat(count("dropped")).isEqualTo(1);
            release.countDown();
        }
        assertThat(count("written")).isEqualTo(2);
    }

    @Test
    void closeFinishesQueuedWritesAndRefusesNewOnes() {
        AtomicInteger runs = new AtomicInteger();
        ObservationQueue queue = new ObservationQueue(8, meters);
        for (int i = 0; i < 5; i++) {
            queue.offer(runs::incrementAndGet);
        }
        queue.close();

        assertThat(runs).hasValue(5);
        assertThat(queue.offer(runs::incrementAndGet)).isFalse();
        assertThat(count("dropped")).isEqualTo(1);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
