package ai.helper2.platform.schema;

import java.sql.SQLException;
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

/**
 * The migrate step of the public image (ADR 0004): {@code java -jar app.jar migrate}. A deployment
 * runs it as a Kubernetes Job, the only workload with the owner's password: Flyway migrates as the
 * owner, then {@link AppLogins} opens the apps' logins, and the process exits.
 *
 * <p>Only a data source and Flyway start: no web server, no Redis, no scheduler, no component scan.
 * Not a {@code @Configuration}, so the apps' component scan never picks it up.
 */
@ImportAutoConfiguration({DataSourceAutoConfiguration.class, FlywayAutoConfiguration.class})
@EnableConfigurationProperties(MigrateStep.LoginProperties.class)
public final class MigrateStep {

  /** The first program argument that runs this step instead of the app. */
  public static final String COMMAND = "migrate";

  /**
   * From {@code helper2.schema}.
   *
   * @param logins the password of each app's role, by role name (H2_DATABASE_PUBLIC_PASSWORD,
   *     H2_DATABASE_BACKOFFICE_PASSWORD)
   */
  @ConfigurationProperties("helper2.schema")
  public record LoginProperties(Map<String, String> logins) {}

  /** True when the program arguments ask for this step. */
  public static boolean requested(String[] args) {
    return args.length > 0 && COMMAND.equals(args[0]);
  }

  /** Migrates (Flyway starts with the context), opens the logins, closes the context. */
  public static void run(String[] args) throws SQLException {
    try (ConfigurableApplicationContext context =
        new SpringApplicationBuilder(MigrateStep.class)
            .web(WebApplicationType.NONE)
            // Connect retries: the same release may restart Postgres (a new Secret), while this
            // runs.
            .properties(
                "spring.flyway.enabled=true",
                "spring.flyway.connect-retries=10",
                "spring.main.banner-mode=off")
            .run(args)) {
      AppLogins.open(
          context.getBean(DataSource.class), context.getBean(LoginProperties.class).logins());
    }
  }
}
