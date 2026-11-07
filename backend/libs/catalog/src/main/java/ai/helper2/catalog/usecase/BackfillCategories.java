package ai.helper2.catalog.usecase;

import ai.helper2.catalog.internal.ResourceStore;
import ai.helper2.identity.AuditTrail;
import ai.helper2.platform.ClusterLock;
import ai.helper2.platform.ClusterLocks;
import ai.helper2.shared.CategoryCatalog;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Assigns the categories of every resource in the catalog again (ADR 0013), in batches of {@link
 * #BATCH} resources, each batch its own transaction. New and changed resources get theirs when they
 * are indexed; this is for the ones indexed before, and for a change of the category words.
 *
 * <p>It runs once at startup when the category words or rules changed since the last run, or a
 * migration ran (a seed writes resources the code did not see): the marker holds the {@link
 * CategoryCatalog#getFingerprint() fingerprint} and the last migration it ran with. It also runs
 * when an admin asks for it. Idempotent: running it twice gives the same labels. One replica at a
 * time.
 */
@Service
public class BackfillCategories {

  private static final Logger log = LoggerFactory.getLogger(BackfillCategories.class);

  static final String MARKER = "categories";
  static final int BATCH = 500;

  /** What one run did. */
  public record Result(int resources, int categorized) {}

  private final ResourceStore store;
  private final CategoryCatalog catalog;
  private final ClusterLock lock;
  private final AuditTrail audit;
  private final Executor background;
  private final Counter categorized;
  private final Counter uncategorized;

  @Autowired
  public BackfillCategories(
      ResourceStore store,
      CategoryCatalog catalog,
      ClusterLocks locks,
      AuditTrail audit,
      MeterRegistry meters) {
    this(
        store,
        catalog,
        locks.keptAlive("category-backfill", Duration.ofMinutes(1)),
        audit,
        meters,
        task -> Thread.ofVirtual().name("category-backfill").start(task));
  }

  BackfillCategories(
      ResourceStore store,
      CategoryCatalog catalog,
      ClusterLock lock,
      AuditTrail audit,
      MeterRegistry meters,
      Executor background) {
    this.store = store;
    this.catalog = catalog;
    this.lock = lock;
    this.audit = audit;
    this.background = background;
    this.categorized = resources(meters, "categorized");
    this.uncategorized = resources(meters, "uncategorized");
  }

  private static Counter resources(MeterRegistry meters, String result) {
    return Counter.builder("helper2.categories.backfill.resources")
        .description("Resources the category backfill assigned categories to, or found none for")
        .tag("result", result)
        .register(meters);
  }

  /**
   * At startup: runs when the category words or rules changed since the last run; null when they
   * did not, or another replica runs it.
   */
  public @Nullable Result runIfChanged() {
    if (store.marker(MARKER).filter(markerValue()::equals).isPresent()) {
      return null;
    }
    return lock.tryCall(this::assignAll);
  }

  /** The admin action: starts a run in the background and answers at once. */
  public void start(String adminId) {
    audit.record(adminId, AuditTrail.Action.BACKFILL_CATEGORIES, null, "STARTED");
    background.execute(this::runLogged);
  }

  private void runLogged() {
    try {
      if (lock.tryCall(this::assignAll) == null) {
        log.info("category backfill not started: another replica runs it");
      }
    } catch (RuntimeException e) {
      log.warn("category backfill failed: {}", e.getClass().getSimpleName());
    }
  }

  /** The category words and rules, and the last migration: a change of either runs it again. */
  String markerValue() {
    return catalog.getFingerprint() + "/" + store.lastMigration();
  }

  private Result assignAll() {
    // Read before the run: a migration that lands meanwhile triggers the next one.
    String value = markerValue();
    int resources = 0;
    int withCategory = 0;
    UUID after = null;
    List<UUID> batch;
    while (!(batch = store.resourceIdsAfter(after, BATCH)).isEmpty()) {
      int found = store.refreshCategories(batch);
      resources += batch.size();
      withCategory += found;
      categorized.increment(found);
      uncategorized.increment(batch.size() - found);
      after = batch.getLast();
    }
    store.mark(MARKER, value);
    Result result = new Result(resources, withCategory);
    log.info("category backfill: {}", result);
    return result;
  }
}
