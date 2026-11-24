package ai.searchavo.research.internal;

import ai.searchavo.research.usecase.FailStaleResearch;
import ai.searchavo.research.usecase.RunResearch;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

class ResearchWorkers implements SmartLifecycle {

  private static final Logger log = LoggerFactory.getLogger(ResearchWorkers.class);
  private static final int STALE_CHECK_EVERY = 30;

  private final RunResearch research;
  private final FailStaleResearch stale;
  private final IntSupplier concurrency;
  private final Duration tick;
  private final AtomicInteger running = new AtomicInteger();
  private ScheduledExecutorService ticker;
  private ExecutorService workers;
  private int ticks;
  private volatile boolean started;

  ResearchWorkers(
      RunResearch research, FailStaleResearch stale, IntSupplier concurrency, Duration tick) {
    this.research = research;
    this.stale = stale;
    this.concurrency = concurrency;
    this.tick = tick;
  }

  @Override
  public void start() {
    workers = Executors.newVirtualThreadPerTaskExecutor();
    ticker =
        Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("research-tick").factory());
    ticker.scheduleWithFixedDelay(
        this::tick, tick.toMillis(), tick.toMillis(), TimeUnit.MILLISECONDS);
    started = true;
  }

  void tick() {
    try {
      if (++ticks % STALE_CHECK_EVERY == 0) {
        int failed = stale.failStale();
        if (failed > 0) {
          log.warn("research: {} running runs lost their worker and failed", failed);
        }
      }
      int limit = Math.max(0, concurrency.getAsInt());
      while (running.get() < limit) {
        running.incrementAndGet();
        workers.submit(this::work);
      }
    } catch (RuntimeException e) {
      log.warn("research tick failed: {}", e.getClass().getSimpleName());
    }
  }

  private void work() {
    try {
      research.runNext();
    } catch (RuntimeException e) {
      log.warn("research run failed: {}", e.getClass().getSimpleName());
    } finally {
      running.decrementAndGet();
    }
  }

  @Override
  public void stop() {
    started = false;
    if (ticker != null) {
      ticker.shutdownNow();
    }
    if (workers != null) {
      workers.shutdown();
    }
  }

  @Override
  public boolean isRunning() {
    return started;
  }
}
