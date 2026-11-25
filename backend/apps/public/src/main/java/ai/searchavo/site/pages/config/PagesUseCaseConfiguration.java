package ai.searchavo.site.pages.config;

import ai.searchavo.catalog.usecase.GetResourceDetail;
import ai.searchavo.catalog.usecase.ListIndexableResources;
import ai.searchavo.site.pages.SiteLists;
import ai.searchavo.site.pages.usecase.ListSitemap;
import ai.searchavo.site.pages.usecase.RenderPage;
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
