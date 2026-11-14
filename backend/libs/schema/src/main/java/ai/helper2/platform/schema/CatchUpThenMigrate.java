package ai.helper2.platform.schema;

import java.util.Arrays;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

final class CatchUpThenMigrate implements FlywayMigrationStrategy {

  static final String SCRIPT = "db/catch-up/V1__catch_up.sql";

  private static final Logger log = LoggerFactory.getLogger(CatchUpThenMigrate.class);

  private final DataSource dataSource;

  CatchUpThenMigrate(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  @Override
  public void migrate(Flyway flyway) {
    if (hasAnAppliedSchema(flyway)) {
      log.warn("Schema catch-up: applying {} and aligning the Flyway history with V1", SCRIPT);
      new ResourceDatabasePopulator(new ClassPathResource(SCRIPT)).execute(dataSource);
      flyway.repair();
    }
    flyway.migrate();
  }

  private static boolean hasAnAppliedSchema(Flyway flyway) {
    return Arrays.stream(flyway.info().applied()).anyMatch(m -> m.getVersion() != null);
  }
}
