package ai.helper2.identity.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.identity.Users;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PurgeInactiveAccountsTest {

  static final Instant NOW = Instant.parse("2026-11-07T03:17:00Z");

  static final class RecordingUsers implements Users {
    final List<Instant> cutoffs = new ArrayList<>();

    @Override
    public int deleteSignedInLastBefore(Instant cutoff) {
      cutoffs.add(cutoff);
      return 3;
    }

    @Override
    public Optional<UUID> find(String provider, String subject) {
      throw new UnsupportedOperationException();
    }

    @Override
    public UUID signedIn(String provider, String subject, String displayName) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<Preferences> preferences(UUID userId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<Preferences> changeRadarMotion(UUID userId, boolean radarMotion) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void delete(UUID userId) {
      throw new UnsupportedOperationException();
    }
  }

  @Test
  void deletesTheAccountsNobodySignedInToForThePeriod() {
    RecordingUsers users = new RecordingUsers();

    int deleted =
        new PurgeInactiveAccounts(users, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofDays(730))
            .purge();

    assertThat(deleted).isEqualTo(3);
    assertThat(users.cutoffs).containsExactly(NOW.minus(Duration.ofDays(730)));
  }
}
