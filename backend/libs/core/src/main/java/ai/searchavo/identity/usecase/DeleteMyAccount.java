package ai.searchavo.identity.usecase;

import ai.searchavo.identity.Users;
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
