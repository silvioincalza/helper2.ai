package ai.searchavo.site.csp;

import ai.searchavo.site.monetisation.CurrentMonetisation;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.web.header.HeaderWriter;
import org.springframework.security.web.header.writers.StaticHeadersWriter;

@Configuration(proxyBeanMethods = false)
class PublicSiteCsp {
  @Bean
  HeaderWriter contentSecurityPolicy(CurrentMonetisation monetisation) {
    return new CspHeaderWriter(() -> ContentSecurityPolicy.forPublicSite(monetisation.now()));
  }

  @Bean
  HeaderWriter strictContentSecurityPolicy() {
    return new StaticHeadersWriter(CspHeaderWriter.HEADER, ContentSecurityPolicy.STRICT);
  }
}
