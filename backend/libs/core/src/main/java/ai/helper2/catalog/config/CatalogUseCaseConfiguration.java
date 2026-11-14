package ai.helper2.catalog.config;

import ai.helper2.catalog.CatalogWriter;
import ai.helper2.catalog.EnrichmentFacts;
import ai.helper2.catalog.ResourceCards;
import ai.helper2.catalog.Resources;
import ai.helper2.catalog.usecase.GetResourceDetail;
import ai.helper2.catalog.usecase.ListIndexableResources;
import ai.helper2.catalog.usecase.ListRecentResources;
import ai.helper2.catalog.usecase.ListUpcomingEvents;
import ai.helper2.catalog.usecase.ReportIssue;
import ai.helper2.search.DescribedSource;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class CatalogUseCaseConfiguration {

  @Bean
  ResourceCards resourceCards(Resources resources, List<DescribedSource> sources) {
    return new ResourceCards(resources, sources);
  }

  @Bean
  GetResourceDetail getResourceDetail(
      Resources resources, EnrichmentFacts enrichment, ResourceCards cards) {
    return new GetResourceDetail(resources, enrichment, cards);
  }

  @Bean
  ListIndexableResources listIndexableResources(Resources resources) {
    return new ListIndexableResources(resources);
  }

  @Bean
  ListRecentResources listRecentResources(Resources resources, ResourceCards cards) {
    return new ListRecentResources(resources, cards);
  }

  @Bean
  ListUpcomingEvents listUpcomingEvents(Resources resources, ResourceCards cards) {
    return new ListUpcomingEvents(resources, cards);
  }

  @Bean
  ReportIssue reportIssue(CatalogWriter writer) {
    return new ReportIssue(writer);
  }
}
