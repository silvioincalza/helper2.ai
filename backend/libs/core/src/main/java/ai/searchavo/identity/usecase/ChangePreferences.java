package ai.searchavo.identity.usecase;

import ai.searchavo.identity.Users;
import ai.searchavo.identity.Users.Preferences;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public class ChangePreferences {

  public sealed interface Outcome {
    record Changed(Preferences preferences) implements Outcome {}

    record NoSuchUser() implements Outcome {}
  }

  private final Users users;

  public ChangePreferences(Users users) {
    this.users = users;
  }

  public Outcome change(UUID userId, @Nullable Boolean radarMotion) {
    Optional<Preferences> changed =
        radarMotion == null
            ? users.preferences(userId)
            : users.changeRadarMotion(userId, radarMotion);
    return changed.<Outcome>map(Outcome.Changed::new).orElseGet(Outcome.NoSuchUser::new);
  }
}
