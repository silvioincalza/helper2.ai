package ai.searchavo.identity.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import ai.searchavo.identity.Users;
import ai.searchavo.identity.Users.Preferences;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ChangePreferencesTest {

  private final UUID user = UUID.randomUUID();
  private final Accounts accounts = new Accounts();
  private final ChangePreferences changePreferences = new ChangePreferences(accounts);

  @Test
  void aChangeAnswersWithThePreferencesAsSaved() {
    accounts.preferences.put(user, new Preferences(true));

    assertThat(changePreferences.change(user, false))
        .isEqualTo(new ChangePreferences.Outcome.Changed(new Preferences(false)));
    assertThat(accounts.preferences).containsEntry(user, new Preferences(false));
  }

  @Test
  void aChangeOfNothingAnswersWithThePreferencesAsTheyAre() {
    accounts.preferences.put(user, new Preferences(true));

    assertThat(changePreferences.change(user, null))
        .isEqualTo(new ChangePreferences.Outcome.Changed(new Preferences(true)));
  }

  @Test
  void anAccountThatIsGoneIsNoSuchUser() {
    assertThat(changePreferences.change(user, false))
        .isEqualTo(new ChangePreferences.Outcome.NoSuchUser());
    assertThat(changePreferences.change(user, null))
        .isEqualTo(new ChangePreferences.Outcome.NoSuchUser());
  }

  private static final class Accounts implements Users {

    private final Map<UUID, Preferences> preferences = new HashMap<>();

    @Override
    public Optional<Preferences> preferences(UUID userId) {
      return Optional.ofNullable(preferences.get(userId));
    }

    @Override
    public Optional<Preferences> changeRadarMotion(UUID userId, boolean radarMotion) {
      return Optional.ofNullable(
          preferences.computeIfPresent(userId, (id, old) -> new Preferences(radarMotion)));
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
    public void delete(UUID userId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public int deleteSignedInLastBefore(Instant cutoff) {
      throw new UnsupportedOperationException();
    }
  }
}
