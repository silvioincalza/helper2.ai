package ai.helper2.identity;

import ai.helper2.platform.AuditTrail;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public interface AuditLog {
  Duration SHORTEST_RETENTION = Duration.ofDays(730);

  record Entry(
      UUID id,
      String adminId,
      AuditTrail.Action action,
      @Nullable String targetId,
      Instant at,
      String outcome,
      @Nullable String before,
      @Nullable String after,
      @Nullable String reason) {
    public Entry(
        UUID id,
        String adminId,
        AuditTrail.Action action,
        @Nullable String targetId,
        Instant at,
        String outcome) {
      this(id, adminId, action, targetId, at, outcome, null, null, null);
    }
  }

  record Page(List<Entry> items, long total) {}

  Page page(int page, int size);

  int deleteBefore(Instant cutoff);
}
