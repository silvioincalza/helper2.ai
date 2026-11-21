package ai.helper2.catalog;

import ai.helper2.shared.CanonicalUrl;
import java.util.List;

/**
 * Which resources the list pages of directory hosts name. Such a page is never a resource of the
 * catalog: it is kept here by its address only.
 */
public interface DirectoryListIndex {

  /** Replaces what one list page names; an entry that is not in the catalog is left out. */
  void replace(DirectoryList list);

  record DirectoryList(CanonicalUrl url, String title, List<Entry> entries) {}

  record Entry(CanonicalUrl resource, String headingPath) {}
}
