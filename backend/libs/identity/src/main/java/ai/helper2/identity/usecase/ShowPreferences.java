package ai.helper2.identity.usecase;

import ai.helper2.identity.Users;
import ai.helper2.identity.Users.Preferences;
import java.util.Optional;
import java.util.UUID;

/** The display settings of a signed-in user, for GET /me. Empty when the account is gone. */
public class ShowPreferences {

  private final Users users;

  public ShowPreferences(Users users) {
    this.users = users;
  }

  public Optional<Preferences> of(UUID userId) {
    return users.preferences(userId);
  }
}
