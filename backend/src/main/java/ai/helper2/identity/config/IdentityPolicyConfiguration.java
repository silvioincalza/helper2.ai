package ai.helper2.identity.config;

import ai.helper2.identity.AccountPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Identity's numbers for GET /api/v1/policy (ADR 0052). */
@Configuration(proxyBeanMethods = false)
class IdentityPolicyConfiguration {

  @Bean
  AccountPolicy accountPolicy(IdentityRetentionProperties retention) {
    return new AccountPolicy(retention.inactiveAccounts());
  }
}
