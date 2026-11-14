package ai.helper2.catalog;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public interface CategoryLabels {
  List<UUID> resourceIdsAfter(@Nullable UUID after, int limit);

  int refreshCategories(Collection<UUID> resourceIds);
}
