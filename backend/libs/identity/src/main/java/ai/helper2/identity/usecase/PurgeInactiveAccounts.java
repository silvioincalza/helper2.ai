package ai.helper2.identity.usecase;

import ai.helper2.identity.Users;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PurgeInactiveAccounts {

  private static final Logger log = LoggerFactory.getLogger(PurgeInactiveAccounts.class);

  private final Users users;
  private final Clock clock;
  private final Duration inactiveAccounts;

  public PurgeInactiveAccounts(Users users, Clock clock, Duration inactiveAccounts) {
    this.users = users;
    this.clock = clock;
    this.inactiveAccounts = inactiveAccounts;
  }

  public int purge() {
    int deleted = users.deleteSignedInLastBefore(clock.instant().minus(inactiveAccounts));
    log.info("account retention: {} inactive accounts deleted", deleted);
    return deleted;
  }
}
