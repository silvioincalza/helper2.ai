package ai.helper2;

import ai.helper2.identity.config.SignInPages;
import ai.helper2.platform.RateLimitKey;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression("'${helper2.test.client:owner}' != 'h2_backoffice'")
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
