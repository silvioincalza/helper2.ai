package ai.helper2.identity.usecase;

import ai.helper2.identity.internal.Users;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * A signed-in user deletes their account: the app_user row and, with it, every bookmark. Issue reports they sent
 * stay, without a reporter (the reviewer still needs them). The caller ends the session.
 */
@Service
public class DeleteMyAccount {

    private final Users users;

    public DeleteMyAccount(Users users) {
        this.users = users;
    }

    public void delete(UUID userId) {
        users.delete(userId);
    }
}
