package ai.helper2.catalog;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public interface CuratedFields {

  record Current(@Nullable String value, boolean curated) {}

  Optional<Current> current(UUID resourceId, String field);

  boolean set(UUID resourceId, String field, String column, boolean bool, String value);

  boolean setDescription(UUID resourceId, String description, String origin);

  boolean setPublished(UUID resourceId, LocalDate publishedAt);

  boolean exists(UUID resourceId);

  int rejectAiValues(UUID resourceId, String field);
}
