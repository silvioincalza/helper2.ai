package ai.helper2.site.pages.config;

import ai.helper2.catalog.usecase.GetResourceDetail;
import ai.helper2.catalog.usecase.ListIndexableResources;
import ai.helper2.site.pages.SiteLists;
import ai.helper2.site.pages.usecase.ListSitemap;
import ai.helper2.site.pages.usecase.RenderPage;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class PagesUseCaseConfiguration {

  @Bean
  RenderPage renderPage(GetResourceDetail details, SiteLists lists, PagesProperties p) {
    return new RenderPage(details, lists, p.publicBaseUrl(), p.noindex());
  }

  @Bean
  ListSitemap listSitemap(
      ListIndexableResources resources, SiteLists lists, Clock clock, PagesProperties p) {
    return new ListSitemap(resources, lists, clock, p.publicBaseUrl(), p.sitemapLimit());
  }
}
