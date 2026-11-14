package ai.helper2.catalog.internal;

import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;

public class CatalogBackOfficeAdapters {

  @Bean
  JdbcCatalogRetention jdbcCatalogRetention(JdbcClient jdbc, JdbcCatalogWriter writer) {
    return new JdbcCatalogRetention(jdbc, writer);
  }
}
