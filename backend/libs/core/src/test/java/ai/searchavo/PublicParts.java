package ai.searchavo;

import ai.searchavo.identity.config.SignInPages;
import ai.searchavo.platform.RateLimitKey;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression("'${searchavo.test.client:owner}' != 'h2_backoffice'")
class PublicParts {

  @Bean
  RateLimitKey rateLimitKey() {
    return RateLimitKey.CLIENT_ADDRESS;
  }

  @Bean
  SignInPages signInPages() {
    return SignInPages.at("/");
  }
}
