package ai.helper2.catalog.config;

import ai.helper2.catalog.ResourceCards;
import ai.helper2.catalog.Resources;
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
}
