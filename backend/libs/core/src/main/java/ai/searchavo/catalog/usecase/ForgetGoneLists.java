package ai.searchavo.catalog.usecase;

import ai.searchavo.catalog.CategoryLabels;
import ai.searchavo.catalog.GoneLists;
import ai.searchavo.shared.UnitOfWork;
import java.util.Collection;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Removes what a curated list named once its repository is gone at GitHub (UC-030 BR-012), and
 * refreshes the categories of the resources it named.
 */
public class ForgetGoneLists {

  private static final Logger log = LoggerFactory.getLogger(ForgetGoneLists.class);

  private final GoneLists lists;
  private final CategoryLabels resources;
  private final UnitOfWork unitOfWork;

  public ForgetGoneLists(GoneLists lists, CategoryLabels resources, UnitOfWork unitOfWork) {
    this.lists = lists;
    this.resources = resources;
    this.unitOfWork = unitOfWork;
  }

  public int forget(Collection<UUID> notFoundRecords, int notFoundVisits) {
    if (notFoundRecords.isEmpty()) {
      return 0;
    }
    GoneLists.Forgotten forgotten =
        unitOfWork.run(() -> forgetAndRefresh(notFoundRecords, notFoundVisits));
    if (forgotten.lists() > 0) {
      log.info(
          "gone lists: {} lists no longer name their {} resources",
          forgotten.lists(),
          forgotten.noLongerNamed().size());
    }
    return forgotten.lists();
  }

  // UC-030 BR-012: the rows of a list and the categories of what it named change together. A
  // refresh that fails leaves the rows, so the next visit that does not find the list tries again.
  private GoneLists.Forgotten forgetAndRefresh(Collection<UUID> notFoundRecords, int visits) {
    GoneLists.Forgotten forgotten = lists.forget(notFoundRecords, visits);
    if (forgotten.lists() > 0) {
      resources.refreshCategories(forgotten.noLongerNamed());
    }
    return forgotten;
  }
}
