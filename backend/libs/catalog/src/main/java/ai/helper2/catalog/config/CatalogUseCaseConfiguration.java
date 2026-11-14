package ai.helper2.catalog.config;

import ai.helper2.catalog.EnrichmentFacts;
import ai.helper2.catalog.ResourceCards;
import ai.helper2.catalog.Resources;
import ai.helper2.catalog.usecase.GetResourceDetail;
import ai.helper2.catalog.usecase.ListIndexableResources;
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
}
