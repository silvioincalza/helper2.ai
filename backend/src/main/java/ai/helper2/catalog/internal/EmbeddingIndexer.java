package ai.helper2.catalog.internal;

import ai.helper2.catalog.Embedder;
import ai.helper2.catalog.Embedder.EmbeddingFailure;
import ai.helper2.catalog.Embedder.FailureReason;
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
 * After a rate limit it pauses. When the API rejects a batch (an error answer, not a refused call
 * or a timeout), up to [singlesAfterFailure] of its texts are sent one at a time in the same tick:
 * NVIDIA's API failed most calls of two or more texts while every single text went through (5 Nov
 * 2026), and the same batch failed every tick. Any other failure is logged and retried next tick.
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
  private final int singlesAfterFailure;
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
      int singlesAfterFailure,
      Duration pauseAfterRateLimit,
      Clock clock,
      MeterRegistry registry) {
    this.store = store;
    this.embedder = embedder;
    this.batch = batch;
    this.singlesAfterFailure = singlesAfterFailure;
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
      backlog.set(pending.backlog());
      if (pending.tasks().isEmpty()) {
        upToDate();
        return 0;
      }
      caughtUp = false;
      int embedded = embed(pending.tasks());
      backlog.set(pending.backlog() - embedded);
      return embedded;
    } catch (DataAccessException e) {
      databaseFailed.increment();
      log.warn("Embedding batch failed: {}", e.getClass().getSimpleName());
      return 0;
    }
  }

  private void upToDate() {
    succeeded();
    if (!caughtUp) {
      caughtUp = true;
      log.info("Embedding index up to date: {}", store.embeddingCoverage(embedder.model()));
    }
  }

  /** The whole batch in one call; one text at a time when the API rejects the batch. */
  private int embed(List<EmbeddingTask> tasks) {
    try {
      return save(tasks);
    } catch (EmbeddingFailure e) {
      failed(e);
      return rejectedByTheApi(e) && tasks.size() > 1 ? oneAtATime(tasks) : 0;
    }
  }

  /**
   * Up to [singlesAfterFailure] texts, each in its own call. A text that fails is left for a later
   * tick; a rate limit, a refused call or a timeout stops the round.
   */
  private int oneAtATime(List<EmbeddingTask> tasks) {
    int embedded = 0;
    for (EmbeddingTask task : tasks.stream().limit(singlesAfterFailure).toList()) {
      try {
        embedded += save(List.of(task));
      } catch (EmbeddingFailure e) {
        failed(e);
        if (!rejectedByTheApi(e)) {
          break;
        }
      }
    }
    return embedded;
  }

  private int save(List<EmbeddingTask> tasks) {
    List<float[]> vectors =
        embedder.embed(tasks.stream().map(EmbeddingTask::text).toList(), Embedder.Kind.PASSAGE);
    store.saveEmbeddings(embedder.model(), tasks, vectors);
    succeeded();
    return tasks.size();
  }

  /** The API answered with an error: the call reached it, so a smaller call may go through. */
  private static boolean rejectedByTheApi(EmbeddingFailure e) {
    return e.reason() == FailureReason.PROVIDER_ERROR;
  }

  private void failed(EmbeddingFailure e) {
    if (e.rateLimited()) {
      pausedUntil = clock.instant().plus(pauseAfterRateLimit);
      rateLimited.increment();
    } else {
      embeddingFailed.increment();
    }
    log.warn("Embedding batch failed: {}", e.getMessage());
  }

  private void succeeded() {
    lastSuccessEpochSeconds.set(clock.instant().getEpochSecond());
  }
}
