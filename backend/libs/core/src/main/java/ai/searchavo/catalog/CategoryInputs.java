package ai.searchavo.catalog;

import ai.searchavo.shared.CategoryInput;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public interface CategoryInputs {
  List<UUID> resourceIdsAfter(@Nullable UUID after, int limit);

  List<Categorizable> of(Collection<UUID> resourceIds);

  record Categorizable(UUID resourceId, CategoryInput input) {}
}
