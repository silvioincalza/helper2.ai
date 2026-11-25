package ai.searchavo.identity.usecase;

import ai.searchavo.identity.Users;
import ai.searchavo.identity.Users.Preferences;
import java.util.Optional;
import java.util.UUID;

public class ShowPreferences {

  private final Users users;

  public ShowPreferences(Users users) {
    this.users = users;
  }

  public Optional<Preferences> of(UUID userId) {
    return users.preferences(userId);
  }
}
