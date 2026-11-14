package ai.helper2.site.csp;

import ai.helper2.site.monetisation.Monetisation;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.web.header.HeaderWriter;
import org.springframework.security.web.header.writers.StaticHeadersWriter;

@Configuration(proxyBeanMethods = false)
class PublicSiteCsp {
  @Bean
  HeaderWriter contentSecurityPolicy(Monetisation monetisation) {
    return new CspHeaderWriter(ContentSecurityPolicy.forPublicSite(monetisation));
  }

  @Bean
  HeaderWriter strictContentSecurityPolicy() {
    return new StaticHeadersWriter(CspHeaderWriter.HEADER, ContentSecurityPolicy.STRICT);
  }
}
