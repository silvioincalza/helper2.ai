package ai.helper2.catalog;

import java.util.UUID;

public interface CuratedFields {
  boolean set(UUID resourceId, String field, String column, boolean bool, String value);

  boolean exists(UUID resourceId);

  int rejectAiValues(UUID resourceId, String field);
}
