package ai.searchavo.platform;

import ai.searchavo.platform.config.CategoriesProperties;
import ai.searchavo.shared.CategoryCatalog;
import ai.searchavo.shared.CategoryMatcher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class CategoryConfiguration {

  @Bean
  CategoryCatalog categoryCatalog(CategoriesProperties properties) {
    return properties.catalog();
  }

  @Bean
  CategoryMatcher categoryMatcher(CategoryCatalog catalog) {
    return new CategoryMatcher(catalog);
  }
}
