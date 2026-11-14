package ai.helper2.catalog.schedule;

import ai.helper2.catalog.config.CatalogProperties;
import ai.helper2.catalog.usecase.BackfillCategories;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
class CategoryBackfillAtStartup {

  private static final Logger log = LoggerFactory.getLogger(CategoryBackfillAtStartup.class);

  private final BackfillCategories backfill;
  private final boolean enabled;

  CategoryBackfillAtStartup(BackfillCategories backfill, CatalogProperties properties) {
    this.backfill = backfill;
    this.enabled = properties.categories().backfillAtStartup();
  }

  @EventListener(ApplicationReadyEvent.class)
  void ready() {
    if (!enabled) {
      return;
    }
    Thread.ofVirtual().name("category-backfill").start(this::run);
  }

  private void run() {
    try {
      if (backfill.runIfChanged() == null) {
        log.debug("category backfill at startup: nothing changed, or another replica runs it");
      }
    } catch (RuntimeException e) {
      log.warn("category backfill at startup failed: {}", e.getClass().getSimpleName());
    }
  }
}
