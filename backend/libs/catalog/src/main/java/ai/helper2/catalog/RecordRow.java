package ai.helper2.catalog;

import ai.helper2.search.SourceHit;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record RecordRow(
    UUID id,
    String sourceId,
    String externalId,
    UUID resourceId,
    String sourceUrl,
    Instant retrievedAt,
    String fingerprint,
    @Nullable DataOrigin strongestOriginThatWroteIt,
    SourceHit hit) {

  public boolean isOfOriginOrStronger(DataOrigin origin) {
    return strongestOriginThatWroteIt == null || strongestOriginThatWroteIt.compareTo(origin) >= 0;
  }
}
