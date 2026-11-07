package ai.helper2.identity;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Outbound port: the accounts. A provider's subject, a display name, the last sign-in and the
 * display settings, nothing else.
 */
public interface Users {

  /** A signed-in user's display settings. */
  record Preferences(boolean radarMotion) {}

  /** The local id of (provider, subject), while that account exists. */
  Optional<UUID> find(String provider, String subject);

  /** Upsert by (provider, subject), on sign-in: refreshes the display name and the last sign-in. */
  UUID signedIn(String provider, String subject, String displayName);

  /** The settings of the account, while it exists. */
  Optional<Preferences> preferences(UUID userId);

  /** Sets whether the Home radar moves; returns the settings after the change. */
  Optional<Preferences> changeRadarMotion(UUID userId, boolean radarMotion);

  /**
   * Deletes the account; its bookmarks go with it, its issue reports stay without a reporter
   * (foreign keys).
   */
  void delete(UUID userId);

  /**
   * Deletes the accounts whose last sign-in is before [cutoff], as {@link #delete} does; returns
   * how many.
   */
  int deleteSignedInLastBefore(Instant cutoff);
}
