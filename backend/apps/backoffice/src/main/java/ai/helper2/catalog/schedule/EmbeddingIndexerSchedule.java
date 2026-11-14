package ai.helper2.catalog.schedule;

import ai.helper2.catalog.config.CatalogProperties;
import ai.helper2.catalog.usecase.IndexEmbeddings;
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

@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression("'${helper2.embedding.api-key:}' != ''")
class EmbeddingIndexerSchedule {

  private static final Logger log = LoggerFactory.getLogger(EmbeddingIndexerSchedule.class);

  @Bean(initMethod = "start", destroyMethod = "stop")
  IndexerSchedule indexerSchedule(
      IndexEmbeddings indexer, LockingTaskExecutor locks, CatalogProperties catalog) {
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

  static Duration tickWorstCase(Duration timeout, int singlesAfterFailure) {
    return timeout.multipliedBy(3L * (1 + singlesAfterFailure)).plusMinutes(1);
  }

  static class IndexerSchedule {
    private final IndexEmbeddings indexer;
    private final ClusterLock lock;
    private final Duration initialDelay;
    private final Duration every;
    private ScheduledExecutorService executor;

    IndexerSchedule(
        IndexEmbeddings indexer, ClusterLock lock, Duration initialDelay, Duration every) {
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

    private void runSafely() {
      try {
        lock.tryRun(indexer::tick);
      } catch (RuntimeException e) {
        log.warn("Embedding indexer tick failed: {}", e.getClass().getSimpleName());
      }
    }
  }
}
