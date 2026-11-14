package ai.helper2.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.identity.AuditLog;
import org.junit.jupiter.api.Test;

class JdbcAuditLogTest {
  @Test
  void thePurgeKeepsWhatTheTriggerKeeps() {
    assertThat(JdbcAuditLog.KEPT_DAYS)
        .isEqualTo(AuditLog.SHORTEST_RETENTION.minusDays(1).toDays())
        .isEqualTo(729);
  }
}
