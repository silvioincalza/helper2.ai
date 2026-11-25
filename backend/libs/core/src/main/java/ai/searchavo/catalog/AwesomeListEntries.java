package ai.searchavo.catalog;

import java.util.List;
import java.util.UUID;

public interface AwesomeListEntries {
  int MAX_DIRECTORY_LISTS = 50;

  ResourceDetail.FoundIn naming(UUID resourceId, int limit);

  /** The lists on directory hosts that name a resource, for the back office only. */
  List<DirectoryList> directoryListsNaming(UUID resourceId);

  record DirectoryList(String title, String page, String headingPath) {}
}
