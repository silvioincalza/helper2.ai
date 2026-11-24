package ai.searchavo.identity.schedule;

import ai.searchavo.identity.Users;
import ai.searchavo.identity.config.IdentityRetentionProperties;
import ai.searchavo.identity.usecase.PurgeInactiveAccounts;
import ai.searchavo.platform.DailyJob;
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
