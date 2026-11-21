package ai.helper2.catalog;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * The curated lists whose own repository is gone at GitHub (UC-030 BR-010). A list that is gone is
 * never read again, so nothing else would remove what it named.
 */
public interface GoneLists {

  /**
   * Removes what each list named whose own source record is among {@code notFoundRecords}, is
   * marked gone, and was not found on at least {@code notFoundVisits} visits in a row.
   */
  Forgotten forget(Collection<UUID> notFoundRecords, int notFoundVisits);

  /** How many lists were forgotten, and the resources they named. */
  record Forgotten(int lists, List<UUID> noLongerNamed) {}
}
