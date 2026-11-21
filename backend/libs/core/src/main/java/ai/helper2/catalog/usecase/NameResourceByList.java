package ai.helper2.catalog.usecase;

import ai.helper2.catalog.CategoryLabels;
import ai.helper2.catalog.ListNamings;
import ai.helper2.shared.CanonicalUrl;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** UC-030 BR-008: the list that proposed a candidate names the resource it became. */
public class NameResourceByList {

  /** A list page of a directory host carries its title; a curated list has none here. */
  public record NamingList(
      CanonicalUrl url, String headingPath, @Nullable String directoryListTitle) {}

  private final ListNamings namings;
  private final CategoryLabels labels;

  public NameResourceByList(ListNamings namings, CategoryLabels labels) {
    this.namings = namings;
    this.labels = labels;
  }

  public void name(UUID resourceId, String title, NamingList list) {
    if (list.directoryListTitle() != null) {
      namings.byDirectoryList(
          list.url(), list.directoryListTitle(), resourceId, list.headingPath());
    } else if (namings.byCuratedList(list.url(), resourceId, title, list.headingPath())) {
      labels.refreshCategories(List.of(resourceId));
    }
  }
}
