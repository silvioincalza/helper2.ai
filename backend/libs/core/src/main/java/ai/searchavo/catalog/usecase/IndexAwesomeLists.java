package ai.searchavo.catalog.usecase;

import ai.searchavo.catalog.AwesomeListIndex;
import ai.searchavo.catalog.AwesomeListText;
import ai.searchavo.catalog.CategoryLabels;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class IndexAwesomeLists {

  private static final Logger log = LoggerFactory.getLogger(IndexAwesomeLists.class);

  private final AwesomeListIndex index;
  private final CategoryLabels resources;

  public IndexAwesomeLists(AwesomeListIndex index, CategoryLabels resources) {
    this.index = index;
    this.resources = resources;
  }

  public int replace(List<AwesomeListText> lists) {
    int written = 0;
    int listsIndexed = 0;
    for (AwesomeListText list : lists) {
      try {
        AwesomeListIndex.Replaced replaced = index.replace(list);
        resources.refreshCategories(replaced.touched());
        written += replaced.written().size();
        listsIndexed++;
      } catch (RuntimeException e) {
        log.warn("awesome list index of {} failed: {}", list.url(), e.getClass().getSimpleName());
      }
    }
    log.info("awesome list index: {} lists, {} entries", listsIndexed, written);
    return written;
  }
}
