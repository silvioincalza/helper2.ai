package ai.helper2.identity.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.helper2.identity.AuditLog;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PurgeExpiredAuditEntriesTest {

  static final Instant NOW = Instant.parse("2026-11-07T03:17:00Z");
  static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  static final class RecordingAuditLog implements AuditLog {
    final List<Instant> cutoffs = new ArrayList<>();

    @Override
    public Page page(int page, int size) {
      throw new UnsupportedOperationException();
    }

    @Override
    public int deleteBefore(Instant cutoff) {
      cutoffs.add(cutoff);
      return 2;
    }
  }

  @Test
  void deletesTheEntriesOlderThanThePeriod() {
    RecordingAuditLog audit = new RecordingAuditLog();

    int deleted = new PurgeExpiredAuditEntries(audit, CLOCK, Duration.ofDays(1000)).purge();

    assertThat(deleted).isEqualTo(2);
    assertThat(audit.cutoffs).containsExactly(NOW.minus(Duration.ofDays(1000)));
  }

  @Test
  void theShortestPeriodIsWhatTheTableKeeps() {
    RecordingAuditLog audit = new RecordingAuditLog();

    new PurgeExpiredAuditEntries(audit, CLOCK, AuditLog.SHORTEST_RETENTION).purge();

    assertThat(audit.cutoffs).containsExactly(NOW.minus(Duration.ofDays(730)));
  }

  @Test
  void aPeriodShorterThanTheTableKeepsIsRefused() {
    assertThatThrownBy(() ->
            new PurgeExpiredAuditEntries(new RecordingAuditLog(), CLOCK, Duration.ofDays(729)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("730");
  }
}
