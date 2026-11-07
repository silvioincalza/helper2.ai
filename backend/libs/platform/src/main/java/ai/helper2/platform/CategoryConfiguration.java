package ai.helper2.platform;

import ai.helper2.platform.config.CategoriesProperties;
import ai.helper2.shared.CategoryCatalog;
import ai.helper2.shared.CategoryMatcher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The category catalog and its matcher (ADR 0013), in every profile: search filters by them, the
 * catalog assigns them, the guide honours the filter.
 */
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
