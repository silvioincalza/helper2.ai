package ai.searchavo.identity.config;

import ai.searchavo.identity.AccountPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class IdentityPolicyConfiguration {

  @Bean
  AccountPolicy accountPolicy(IdentityRetentionProperties retention) {
    return new AccountPolicy(retention.inactiveAccounts(), retention.adminAudit());
  }
}
