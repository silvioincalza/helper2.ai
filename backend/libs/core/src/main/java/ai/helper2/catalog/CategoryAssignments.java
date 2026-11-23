package ai.helper2.catalog;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface CategoryAssignments {
  void replace(Collection<UUID> resourceIds, List<Assignment> assignments);

  record Assignment(UUID resourceId, String category) {}
}
