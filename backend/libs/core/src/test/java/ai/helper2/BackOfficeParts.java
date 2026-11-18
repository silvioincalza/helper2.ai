package ai.helper2;

import ai.helper2.catalog.config.CatalogBackOfficeConfiguration;
import ai.helper2.examples.config.ExamplesBackOfficeConfiguration;
import ai.helper2.identity.config.SignInPages;
import ai.helper2.platform.RateLimitKey;
import ai.helper2.platform.UnitOfWorkConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = TestcontainersConfiguration.CLIENT, havingValue = "h2_backoffice")
@Import({
  CatalogBackOfficeConfiguration.class,
  ExamplesBackOfficeConfiguration.class,
  UnitOfWorkConfiguration.class
})
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
