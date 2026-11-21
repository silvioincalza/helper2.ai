package ai.helper2.catalog.usecase;

import ai.helper2.catalog.CategoryLabels;
import ai.helper2.catalog.GoneLists;
import java.util.Collection;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Removes what a curated list named once its repository is gone at GitHub (UC-030 BR-010), and
 * refreshes the categories of the resources it named.
 */
public class ForgetGoneLists {

  private static final Logger log = LoggerFactory.getLogger(ForgetGoneLists.class);

  private final GoneLists lists;
  private final CategoryLabels resources;

  public ForgetGoneLists(GoneLists lists, CategoryLabels resources) {
    this.lists = lists;
    this.resources = resources;
  }

  public int forget(Collection<UUID> notFoundRecords, int notFoundVisits) {
    if (notFoundRecords.isEmpty()) {
      return 0;
    }
    GoneLists.Forgotten forgotten = lists.forget(notFoundRecords, notFoundVisits);
    if (forgotten.lists() == 0) {
      return 0;
    }
    resources.refreshCategories(forgotten.noLongerNamed());
    log.info(
        "gone lists: {} lists no longer name their {} resources",
        forgotten.lists(),
        forgotten.noLongerNamed().size());
    return forgotten.lists();
  }
}
