package ai.helper2.identity.schedule;

import ai.helper2.identity.Users;
import ai.helper2.identity.config.IdentityRetentionProperties;
import ai.helper2.identity.usecase.PurgeInactiveAccounts;
import ai.helper2.platform.DailyJob;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class AccountRetentionSchedule {

  @Bean
  PurgeInactiveAccounts purgeInactiveAccounts(
      Users users, Clock clock, IdentityRetentionProperties periods) {
    return new PurgeInactiveAccounts(users, clock, periods.inactiveAccounts());
  }

  @Bean
  DailyJob accountRetention(PurgeInactiveAccounts purge) {
    return new DailyJob("accounts", purge::purge);
  }
}
