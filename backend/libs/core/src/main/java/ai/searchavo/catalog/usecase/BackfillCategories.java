package ai.searchavo.catalog.usecase;

import ai.searchavo.catalog.CategoryInputs;
import ai.searchavo.catalog.CategoryLabels;
import ai.searchavo.catalog.JobMarkers;
import ai.searchavo.platform.AuditTrail;
import ai.searchavo.platform.ClusterLock;
import ai.searchavo.shared.CategoryCatalog;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BackfillCategories {

  private static final Logger log = LoggerFactory.getLogger(BackfillCategories.class);

  static final String MARKER = "categories";
  static final int BATCH = 500;

  public record Result(int resources, int categorized) {}

  private final CategoryInputs inputs;
  private final CategoryLabels labels;
  private final JobMarkers markers;
  private final CategoryCatalog catalog;
  private final ClusterLock lock;
  private final AuditTrail audit;
  private final Executor background;
  private final Counter categorized;
  private final Counter uncategorized;

  public BackfillCategories(
      CategoryInputs inputs,
      CategoryLabels labels,
      JobMarkers markers,
      CategoryCatalog catalog,
      ClusterLock lock,
      AuditTrail audit,
      MeterRegistry meters,
      Executor background) {
    this.inputs = inputs;
    this.labels = labels;
    this.markers = markers;
    this.catalog = catalog;
    this.lock = lock;
    this.audit = audit;
    this.background = background;
    this.categorized = resources(meters, "categorized");
    this.uncategorized = resources(meters, "uncategorized");
  }

  private static Counter resources(MeterRegistry meters, String result) {
    return Counter.builder("searchavo.categories.backfill.resources")
        .description("Resources the category backfill assigned categories to, or found none for")
        .tag("result", result)
        .register(meters);
  }

  public @Nullable Result runIfChanged() {
    if (markers.marker(MARKER).filter(markerValue()::equals).isPresent()) {
      return null;
    }
    return lock.tryCall(this::assignAll);
  }

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

  String markerValue() {
    return catalog.getFingerprint() + "/" + markers.lastMigration();
  }

  private Result assignAll() {
    String value = markerValue();
    int resources = 0;
    int withCategory = 0;
    UUID after = null;
    List<UUID> batch;
    while (!(batch = inputs.resourceIdsAfter(after, BATCH)).isEmpty()) {
      int found = labels.refreshCategories(batch);
      resources += batch.size();
      withCategory += found;
      categorized.increment(found);
      uncategorized.increment(batch.size() - found);
      after = batch.getLast();
    }
    markers.mark(MARKER, value);
    Result result = new Result(resources, withCategory);
    log.info("category backfill: {}", result);
    return result;
  }
}
