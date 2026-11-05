package ai.helper2.catalog.usecase;

import ai.helper2.catalog.AwesomeListText;
import ai.helper2.catalog.internal.AwesomeListIndex;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The Scout indexes what awesome lists say around their entries (ADR 0055): each list's index is
 * replaced on its own, so one list that fails does not keep the others stale.
 */
@Service
public class IndexAwesomeLists {

  private static final Logger log = LoggerFactory.getLogger(IndexAwesomeLists.class);

  private final AwesomeListIndex index;

  public IndexAwesomeLists(AwesomeListIndex index) {
    this.index = index;
  }

  /** Returns the entries written across [lists]. */
  public int replace(List<AwesomeListText> lists) {
    int written = 0;
    int listsIndexed = 0;
    for (AwesomeListText list : lists) {
      try {
        written += index.replace(list);
        listsIndexed++;
      } catch (RuntimeException e) {
        log.warn("awesome list index of {} failed: {}", list.url(), e.getClass().getSimpleName());
      }
    }
    log.info("awesome list index: {} lists, {} entries", listsIndexed, written);
    return written;
  }
}
