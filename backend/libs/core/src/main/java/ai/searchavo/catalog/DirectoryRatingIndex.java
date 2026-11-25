package ai.searchavo.catalog;

import ai.searchavo.shared.CanonicalUrl;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * UC-030 BR-010: the figures the list pages of directory hosts, and their pages of an item, state
 * for the resources of the catalog. One record per list and resource; it goes when the list no
 * longer names the resource, and with the resource.
 */
public interface DirectoryRatingIndex {

  /** The page of an item on a directory, for one resource a list of that directory names. */
  record ItemPage(CanonicalUrl list, CanonicalUrl resource, CanonicalUrl page) {}

  /** What one page states for one resource; {@code figures} is null when it states none. */
  record Reading(
      CanonicalUrl resource, CanonicalUrl page, @Nullable StatedFigures figures, Instant readAt) {}

  /**
   * What one pass knows of a list: every resource it names, the figures the list page states next
   * to an entry, and the pages of an item read in this pass.
   */
  record ListRatings(
      CanonicalUrl list,
      String host,
      List<CanonicalUrl> named,
      List<Reading> onTheList,
      List<Reading> onItemPages) {}

  /** The pages whose resource is in the catalog and that were not tried after {@code since}. */
  List<ItemPage> due(List<ItemPage> pages, Instant since);

  /** UC-030 BR-011: every try of a page of an item counts, whatever it gives. */
  int itemPageTriesSince(Instant since);

  void tried(ItemPage page, String host, Instant at);

  void replace(ListRatings list);
}
