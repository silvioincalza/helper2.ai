package ai.helper2.catalog.internal;

import ai.helper2.catalog.Embedder;
import ai.helper2.catalog.Embedder.EmbeddingFailure;
import ai.helper2.catalog.internal.ResourceStore.EmbeddingTask;
import ai.helper2.catalog.internal.ResourceStore.PendingEmbeddings;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;

/**
 * Keeps resource_embedding in step with resource: each tick embeds one batch of resources that have
 * no vector for the current model or whose text changed. The same loop builds the index the first
 * time and keeps it current afterwards, so there is no upsert hook and no separate reindex job.
 * After a rate limit it pauses; any other failure is logged and retried on the next tick.
 *
 * <p>Meters: helper2.embedding.backlog (resources without a current vector for the model: none yet,
 * or one made from text that has changed since; counted by the same query that picks the batch,
 * minus the batch once it is stored, so a failed batch still updates it),
 * helper2.embedding.failures{reason} and helper2.embedding.last.success (epoch seconds of the last
 * batch that stored vectors or found nothing to do; 0 until then).
 */
public class EmbeddingIndexer {

  private static final Logger log = LoggerFactory.getLogger(EmbeddingIndexer.class);

  private final ResourceStore store;
  private final Embedder embedder;
  private final int batch;
  private final Duration pauseAfterRateLimit;
  private final Clock clock;
  private final AtomicLong backlog = new AtomicLong();
  private final AtomicLong lastSuccessEpochSeconds = new AtomicLong();
  private final Counter rateLimited;
  private final Counter embeddingFailed;
  private final Counter databaseFailed;
  private Instant pausedUntil = Instant.MIN;
  private boolean caughtUp;

  public EmbeddingIndexer(
      ResourceStore store,
      Embedder embedder,
      int batch,
      Duration pauseAfterRateLimit,
      Clock clock,
      MeterRegistry registry) {
    this.store = store;
    this.embedder = embedder;
    this.batch = batch;
    this.pauseAfterRateLimit = pauseAfterRateLimit;
    this.clock = clock;
    Gauge.builder("helper2.embedding.backlog", backlog, AtomicLong::get)
        .description(
            "Resources without a current embedding for the model (none yet, or the text changed since)")
        .register(registry);
    Gauge.builder("helper2.embedding.last.success", lastSuccessEpochSeconds, AtomicLong::get)
        .description("Epoch seconds of the last successful indexer batch")
        .baseUnit("seconds")
        .register(registry);
    this.rateLimited = failures(registry, "rate_limited");
    this.embeddingFailed = failures(registry, "embedding");
    this.databaseFailed = failures(registry, "database");
  }

  private static Counter failures(MeterRegistry registry, String reason) {
    return Counter.builder("helper2.embedding.failures")
        .description("Failed embedding indexer batches")
        .tag("reason", reason)
        .register(registry);
  }

  /** One batch. Returns how many resources were embedded (0 when paused, idle or failed). */
  public synchronized int tick() {
    if (clock.instant().isBefore(pausedUntil)) {
      return 0;
    }
    try {
      PendingEmbeddings pending = store.pendingEmbeddings(embedder.model(), batch);
      List<EmbeddingTask> tasks = pending.tasks();
      backlog.set(pending.backlog());
      if (tasks.isEmpty()) {
        succeeded();
        if (!caughtUp) {
          caughtUp = true;
          log.info("Embedding index up to date: {}", store.embeddingCoverage(embedder.model()));
        }
        return 0;
      }
      caughtUp = false;
      List<float[]> vectors =
          embedder.embed(tasks.stream().map(EmbeddingTask::text).toList(), Embedder.Kind.PASSAGE);
      store.saveEmbeddings(embedder.model(), tasks, vectors);
      succeeded();
      backlog.set(pending.backlog() - tasks.size());
      return tasks.size();
    } catch (EmbeddingFailure e) {
      if (e.rateLimited()) {
        pausedUntil = clock.instant().plus(pauseAfterRateLimit);
        rateLimited.increment();
      } else {
        embeddingFailed.increment();
      }
      log.warn("Embedding batch failed: {}", e.getMessage());
      return 0;
    } catch (DataAccessException e) {
      databaseFailed.increment();
      log.warn("Embedding batch failed: {}", e.getClass().getSimpleName());
      return 0;
    }
  }

  private void succeeded() {
    lastSuccessEpochSeconds.set(clock.instant().getEpochSecond());
  }
}
