package ai.helper2.platform;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The public app owns the migrations (ADR 0069): the back office only checks at startup that the
 * database has every migration it was built with. While the public app has not yet migrated (a
 * release with a new migration), the check fails and the back office waits, restarted by
 * Kubernetes, until it has.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SchemaCheck.SchemaProperties.class)
class SchemaCheck {

  /**
   * From {@code helper2.schema}.
   *
   * @param migrate migrate instead of only checking: for the back office's own tests, which have no
   *     public app to migrate their database; never set in a deployment
   */
  @ConfigurationProperties("helper2.schema")
  record SchemaProperties(boolean migrate) {}

  @Bean
  FlywayMigrationStrategy validateOnly(SchemaProperties schema) {
    return schema.migrate() ? flyway -> flyway.migrate() : flyway -> flyway.validate();
  }
}
