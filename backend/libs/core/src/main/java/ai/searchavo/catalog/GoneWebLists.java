package ai.searchavo.catalog;

import ai.searchavo.shared.CanonicalUrl;
import java.time.Instant;

/**
 * The web page lists whose site says the page is gone (UC-030 BR-012): a curated web page, and a
 * list page of a directory host.
 */
public interface GoneWebLists {

  /**
   * Removes what the list at this address named and the ratings its page stated, and marks the own
   * record of a curated web page gone. It counts one list when the catalog held any of them.
   */
  GoneLists.Forgotten forget(CanonicalUrl list, Instant at);
}
