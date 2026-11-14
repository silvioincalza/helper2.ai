package ai.helper2.catalog.usecase;

import ai.helper2.catalog.Embedder;
import ai.helper2.catalog.Embedder.EmbeddingFailure;
import ai.helper2.catalog.Embedder.FailureReason;
import ai.helper2.catalog.EmbeddingStore;
import ai.helper2.catalog.EmbeddingStore.EmbeddingTask;
import ai.helper2.catalog.EmbeddingStore.PendingEmbeddings;
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

public class IndexEmbeddings {

  private static final Logger log = LoggerFactory.getLogger(IndexEmbeddings.class);
  private static final long NOT_COUNTED_YET = -1;

  private final EmbeddingStore store;
  private final Embedder embedder;
  private final int batch;
  private final int singlesAfterFailure;
  private final Duration pauseAfterRateLimit;
  private final Clock clock;
  private final AtomicLong backlog = new AtomicLong(NOT_COUNTED_YET);
  private final AtomicLong lastSuccessEpochSeconds = new AtomicLong();
  private final Counter rateLimited;
  private final Counter embeddingFailed;
  private final Counter databaseFailed;
  private Instant pausedUntil = Instant.MIN;
  private boolean caughtUp;

  public IndexEmbeddings(
      EmbeddingStore store,
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
    Gauge.builder("helper2.embedding.backlog", backlog, IndexEmbeddings::countedOrUnknown)
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

  private static double countedOrUnknown(AtomicLong backlog) {
    long counted = backlog.get();
    return counted == NOT_COUNTED_YET ? Double.NaN : counted;
  }

  private static Counter failures(MeterRegistry registry, String reason) {
    return Counter.builder("helper2.embedding.failures")
        .description("Failed embedding indexer batches")
        .tag("reason", reason)
        .register(registry);
  }

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
    } catch (EmbeddingStore.Unavailable e) {
      databaseFailed.increment();
      log.warn("Embedding batch failed: {}", e.getMessage());
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

  private int embed(List<EmbeddingTask> tasks) {
    try {
      return save(tasks);
    } catch (EmbeddingFailure e) {
      failed(e);
      return rejectedByTheApi(e) && tasks.size() > 1 ? oneAtATime(tasks) : 0;
    }
  }

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
