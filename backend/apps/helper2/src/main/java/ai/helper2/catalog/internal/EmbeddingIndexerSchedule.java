package ai.helper2.catalog.internal;

import ai.helper2.catalog.config.CatalogProperties;
import ai.helper2.platform.ClusterLock;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The embedding indexer's schedule (ADR 0016): the app that runs it declares it, the indexer itself
 * is the catalog library's. On only with an embedding API key, as the indexer.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression("'${helper2.embedding.api-key:}' != ''")
class EmbeddingIndexerSchedule {

  private static final Logger log = LoggerFactory.getLogger(EmbeddingIndexerSchedule.class);

  /**
   * One batch at a time across replicas, and one per period ([every]) in total: the batch size and
   * period keep the embedding API's request rate where it was measured (4 per minute), however many
   * replicas run. At most: every embedding call of a tick (the batch, then up to
   * singles-after-failure texts one per call) can wait for a connection, connect and read, each up
   * to the index timeout, plus three short queries (with 30 s and 5 singles: 10 min), and never
   * less than the least. The lock is not renewed, so a tick that outlived it would let a second
   * replica embed the same resources. At least: 90 % of the period, so a replica whose schedule
   * fires a little later skips that period instead of embedding a second batch in it.
   */
  @Bean(initMethod = "start", destroyMethod = "stop")
  IndexerSchedule embeddingIndexerSchedule(
      EmbeddingIndexer indexer, LockingTaskExecutor locks, CatalogProperties catalog) {
    Duration indexTimeout = catalog.indexer().timeout();
    Duration initialDelay = catalog.indexer().initialDelay();
    Duration every = catalog.indexer().every();
    Duration atLeastFor = every.multipliedBy(9).dividedBy(10);
    Duration batchWorstCase = tickWorstCase(indexTimeout, catalog.indexer().singlesAfterFailure());
    ClusterLock lock =
        new ClusterLock(
            locks,
            "embedding-indexer",
            batchWorstCase.compareTo(atLeastFor) > 0 ? batchWorstCase : atLeastFor,
            atLeastFor);
    return new IndexerSchedule(indexer, lock, initialDelay, every);
  }

  /** Every call of a tick at its worst (wait, connect, read), plus the tick's short queries. */
  static Duration tickWorstCase(Duration timeout, int singlesAfterFailure) {
    return timeout.multipliedBy(3L * (1 + singlesAfterFailure)).plusMinutes(1);
  }

  /**
   * Its own thread, not @Scheduled: with virtual threads Spring runs every fixed-delay task on one
   * scheduler thread, so a Scout run (minutes) stalled the indexer (seen on staging, 27 Oct 2026).
   * Fixed delay, so a slow batch never overlaps the next one.
   */
  static class IndexerSchedule {
    private final EmbeddingIndexer indexer;
    private final ClusterLock lock;
    private final Duration initialDelay;
    private final Duration every;
    private ScheduledExecutorService executor;

    IndexerSchedule(
        EmbeddingIndexer indexer, ClusterLock lock, Duration initialDelay, Duration every) {
      this.indexer = indexer;
      this.lock = lock;
      this.initialDelay = initialDelay;
      this.every = every;
    }

    void start() {
      executor =
          Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "embedding-indexer");
            t.setDaemon(true);
            return t;
          });
      executor.scheduleWithFixedDelay(
          this::runSafely, initialDelay.toMillis(), every.toMillis(), TimeUnit.MILLISECONDS);
    }

    void stop() {
      if (executor != null) {
        executor.shutdownNow();
      }
    }

    /** An exception escaping a scheduled task would cancel all later runs. */
    private void runSafely() {
      try {
        lock.tryRun(indexer::tick);
      } catch (RuntimeException e) {
        log.warn("Embedding indexer tick failed: {}", e.getClass().getSimpleName());
      }
    }
  }
}
