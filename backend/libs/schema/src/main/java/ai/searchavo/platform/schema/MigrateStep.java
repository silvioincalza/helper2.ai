package ai.searchavo.platform.schema;

import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;

@ImportAutoConfiguration({DataSourceAutoConfiguration.class, FlywayAutoConfiguration.class})
@EnableConfigurationProperties(MigrateStep.MigrateProperties.class)
public final class MigrateStep {
  public static final String COMMAND = "migrate";

  @ConfigurationProperties("searchavo.schema")
  public record MigrateProperties(
      Map<String, String> logins,
      Map<String, Duration> statementTimeouts,
      String monitorPassword) {}

  public static boolean requested(String[] args) {
    return args.length > 0 && COMMAND.equals(args[0]);
  }

  public static void run(String[] args) throws SQLException {
    try (ConfigurableApplicationContext context =
        new SpringApplicationBuilder(MigrateStep.class)
            .web(WebApplicationType.NONE)
            .properties(
                "spring.flyway.enabled=true",
                "spring.flyway.connect-retries=10",
                "spring.main.banner-mode=off")
            .run(args)) {
      MigrateProperties schema = context.getBean(MigrateProperties.class);
      DataSource owner = context.getBean(DataSource.class);
      AppLogins.open(owner, schema.logins(), schema.statementTimeouts());
      MonitorLogin.open(owner, schema.monitorPassword());
    }
  }
}
