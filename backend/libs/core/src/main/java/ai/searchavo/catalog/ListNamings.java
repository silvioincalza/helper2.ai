package ai.searchavo.catalog;

import ai.searchavo.shared.CanonicalUrl;
import java.util.UUID;

/**
 * UC-030 BR-008: one list names one resource of the catalog. The record lasts until the next read
 * of the list, which writes again what the list names.
 */
public interface ListNamings {

  /**
   * False when the list is not in the list index (never read, or gone at its source: UC-030
   * BR-012), the resource is not in the catalog, or the list names it already.
   */
  boolean byCuratedList(CanonicalUrl list, UUID resourceId, String text, String headingPath);

  /** UC-030 BR-009: a list page of a directory host is kept by its address and its title. */
  void byDirectoryList(CanonicalUrl list, String title, UUID resourceId, String headingPath);
}
