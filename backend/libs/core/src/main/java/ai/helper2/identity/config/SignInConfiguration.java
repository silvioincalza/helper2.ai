package ai.helper2.identity.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;

@Configuration(proxyBeanMethods = false)
class SignInConfiguration {

  @Bean
  OAuth2AuthorizedClientRepository authorizedClientRepository() {
    return new NoProviderTokens();
  }
}
