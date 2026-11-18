package ai.helper2.catalog;

import java.time.LocalDate;
import java.util.UUID;

public interface CuratedFields {
  boolean set(UUID resourceId, String field, String column, boolean bool, String value);

  boolean setDescription(UUID resourceId, String description, String origin);

  boolean setPublished(UUID resourceId, LocalDate publishedAt);

  boolean exists(UUID resourceId);

  int rejectAiValues(UUID resourceId, String field);
}
