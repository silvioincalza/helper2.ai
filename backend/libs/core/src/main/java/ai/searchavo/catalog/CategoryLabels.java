package ai.searchavo.catalog;

import java.util.Collection;
import java.util.UUID;

public interface CategoryLabels {
  int refreshCategories(Collection<UUID> resourceIds);
}
