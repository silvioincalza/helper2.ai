package ai.helper2.identity.usecase;

import ai.helper2.identity.internal.Users;
import ai.helper2.platform.quota.QuotaCounter;
import ai.helper2.platform.quota.QuotaKeys;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * A signed-in user deletes their account: the app_user row and, with it, every bookmark, then the user's daily
 * web-search counters (ADR 0037). Issue reports they sent stay, without a reporter (the reviewer still needs them).
 * The caller ends the session. While Redis is down the counters stay until they expire, within a day.
 */
@Service
public class DeleteMyAccount {

    private static final Logger log = LoggerFactory.getLogger(DeleteMyAccount.class);

    private final Users users;
    private final QuotaCounter quota;
    private final QuotaKeys quotaKeys;

    public DeleteMyAccount(Users users, QuotaCounter quota, QuotaKeys quotaKeys) {
        this.users = users;
        this.quota = quota;
        this.quotaKeys = quotaKeys;
    }

    public void delete(UUID userId) {
        users.delete(userId);
        try {
            quota.delete(quotaKeys.userKeys(userId));
        } catch (RuntimeException e) {
            log.warn("quota counters of a deleted account left to expire: {}", e.getClass().getSimpleName());
        }
    }
}
