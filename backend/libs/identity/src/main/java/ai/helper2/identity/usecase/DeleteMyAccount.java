package ai.helper2.identity.usecase;

import ai.helper2.identity.Users;
import java.util.UUID;

public class DeleteMyAccount {

  private final Users users;

  public DeleteMyAccount(Users users) {
    this.users = users;
  }

  public void delete(UUID userId) {
    users.delete(userId);
  }
}
