package ai.helper2;

import ai.helper2.catalog.config.EnableCatalogBackOfficeAdapters;
import ai.helper2.identity.config.SignInPages;
import ai.helper2.platform.RateLimitKey;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = TestcontainersConfiguration.CLIENT, havingValue = "h2_backoffice")
@EnableCatalogBackOfficeAdapters
class BackOfficeParts {

  @Bean
  RateLimitKey rateLimitKey() {
    return RateLimitKey.SIGNED_IN_ADMIN;
  }

  @Bean
  SignInPages signInPages() {
    return SignInPages.at("/admin");
  }
}
