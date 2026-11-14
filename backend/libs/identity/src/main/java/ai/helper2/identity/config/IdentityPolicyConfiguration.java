package ai.helper2.identity.config;

import ai.helper2.identity.AccountPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class IdentityPolicyConfiguration {

  @Bean
  AccountPolicy accountPolicy(IdentityRetentionProperties retention) {
    return new AccountPolicy(retention.inactiveAccounts(), retention.adminAudit());
  }
}
