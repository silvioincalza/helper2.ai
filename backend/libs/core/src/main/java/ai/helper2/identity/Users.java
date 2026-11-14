package ai.helper2.identity;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface Users {
  record Preferences(boolean radarMotion) {}

  Optional<UUID> find(String provider, String subject);

  UUID signedIn(String provider, String subject, String displayName);

  Optional<Preferences> preferences(UUID userId);

  Optional<Preferences> changeRadarMotion(UUID userId, boolean radarMotion);

  void delete(UUID userId);

  int deleteSignedInLastBefore(Instant cutoff);
}
