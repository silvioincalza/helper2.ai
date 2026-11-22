package ai.helper2.catalog.usecase;

import ai.helper2.catalog.CategoryLabels;
import ai.helper2.catalog.GoneLists;
import ai.helper2.catalog.GoneWebLists;
import ai.helper2.shared.CanonicalUrl;
import ai.helper2.shared.UnitOfWork;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Removes what a web page list named once its site says the page is gone (UC-030 BR-012), and
 * refreshes the categories of the resources it named.
 */
public class ForgetGoneWebList {

  private static final Logger log = LoggerFactory.getLogger(ForgetGoneWebList.class);

  private final GoneWebLists lists;
  private final CategoryLabels resources;
  private final UnitOfWork unitOfWork;

  public ForgetGoneWebList(GoneWebLists lists, CategoryLabels resources, UnitOfWork unitOfWork) {
    this.lists = lists;
    this.resources = resources;
    this.unitOfWork = unitOfWork;
  }

  /** True when the catalog held something the list named, or the list's own record. */
  public boolean forget(CanonicalUrl list, Instant at) {
    GoneLists.Forgotten forgotten = unitOfWork.run(() -> forgetAndRefresh(list, at));
    if (forgotten.lists() > 0) {
      log.info(
          "gone lists: a web page list on {} no longer names its {} resources",
          list.getHost(),
          forgotten.noLongerNamed().size());
    }
    return forgotten.lists() > 0;
  }

  // UC-030 BR-012: the rows of a list and the categories of what it named change together. A
  // refresh that fails leaves the rows, so the next check that says the page is gone tries again.
  private GoneLists.Forgotten forgetAndRefresh(CanonicalUrl list, Instant at) {
    GoneLists.Forgotten forgotten = lists.forget(list, at);
    if (!forgotten.noLongerNamed().isEmpty()) {
      resources.refreshCategories(forgotten.noLongerNamed());
    }
    return forgotten;
  }
}
