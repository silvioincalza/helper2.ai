package ai.helper2.catalog;

import java.util.UUID;

public interface AwesomeListEntries {
  ResourceDetail.FoundIn naming(UUID resourceId, int limit);
}
