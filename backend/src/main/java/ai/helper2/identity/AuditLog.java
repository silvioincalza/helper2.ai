package ai.helper2.identity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Outbound port: the admin audit trail as the back office reads it and retention deletes it. Other
 * modules write it through {@link AuditTrail}.
 */
public interface AuditLog {

  record Entry(
      UUID id,
      String adminId,
      AuditTrail.Action action,
      @Nullable String targetId,
      Instant at,
      String outcome) {}

  record Page(List<Entry> items, long total) {}

  /** Newest first. */
  Page page(int page, int size);

  /** Deletes the entries written before [cutoff]; returns how many. */
  int deleteBefore(Instant cutoff);
}
