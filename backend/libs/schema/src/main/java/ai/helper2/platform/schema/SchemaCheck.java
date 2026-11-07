package ai.helper2.platform.schema;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.CoreErrorCode;
import org.flywaydb.core.api.ErrorCode;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.exception.FlywayValidateException;
import org.flywaydb.core.api.output.ValidateOutput;
import org.flywaydb.core.api.output.ValidateResult;
import org.flywaydb.core.internal.exception.FlywaySqlException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Neither app migrates in a deployment (ADR 0070): the migrate step (a Kubernetes Job, as the
 * owner) does, and each app checks at startup, as its own role, that the database has every
 * migration it was built with. In a release with a new migration the app starts while the Job still
 * runs: it waits, up to {@code helper2.schema.wait-for}, while migrations are only missing or the
 * database still refuses its login (the Job opens it after migrating), and fails at once on
 * anything else, e.g. a changed migration. The old pods keep serving meanwhile.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SchemaCheck.SchemaProperties.class)
public class SchemaCheck {

  private static final Logger log = LoggerFactory.getLogger(SchemaCheck.class);

  /**
   * The validation errors that mean "not migrated yet", not "wrong": a new versioned migration, and
   * a new or changed repeatable one (the seeds, R__*.sql).
   */
  private static final Set<ErrorCode> NOT_YET =
      Set.of(
          CoreErrorCode.RESOLVED_VERSIONED_MIGRATION_NOT_APPLIED,
          CoreErrorCode.RESOLVED_REPEATABLE_MIGRATION_NOT_APPLIED,
          CoreErrorCode.OUTDATED_REPEATABLE_MIGRATION);

  /**
   * From {@code helper2.schema}.
   *
   * @param migrate migrate instead of only checking: local development only (the dev profile,
   *     compose), where the app connects as the owner; never set in a deployment
   * @param waitFor how long the check waits for the migrate step; below the startup probe's budget
   *     (app.startupSeconds in the chart)
   * @param retryEvery the pause between two checks
   */
  @ConfigurationProperties("helper2.schema")
  public record SchemaProperties(
      boolean migrate,
      @DefaultValue("PT4M") Duration waitFor,
      @DefaultValue("PT5S") Duration retryEvery) {}

  @Bean
  FlywayMigrationStrategy schemaStrategy(SchemaProperties schema) {
    if (schema.migrate()) {
      return Flyway::migrate;
    }
    return flyway -> awaitMigrations(flyway, schema, Clock.systemUTC(), SchemaCheck::sleep);
  }

  /** Returns once the schema validates; throws when it is wrong or the wait is over. */
  public static void awaitMigrations(
      Flyway flyway, SchemaProperties schema, Clock clock, Sleeper sleeper) {
    Instant deadline = clock.instant().plus(schema.waitFor());
    while (true) {
      String waitingFor = check(flyway);
      if (waitingFor == null) {
        return;
      }
      if (!clock.instant().isBefore(deadline)) {
        throw new FlywayException(
            "The database still lacks "
                + waitingFor
                + " after "
                + schema.waitFor()
                + ": did the migrate Job run? (ADR 0070)");
      }
      log.info("Waiting for the migrate step: {}", waitingFor);
      sleeper.sleep(schema.retryEvery());
    }
  }

  /** Null when the schema is right, what is missing while it is not there yet; throws if wrong. */
  private static String check(Flyway flyway) {
    ValidateResult result;
    try {
      result = flyway.validateWithResult();
    } catch (FlywaySqlException e) {
      // No connection, or no login yet: the migrate Job opens the role's login after migrating.
      return "a login (" + e.getMessage().lines().findFirst().orElse("") + ")";
    }
    if (result.validationSuccessful) {
      return null;
    }
    if (!onlyNotYetApplied(result.invalidMigrations)) {
      throw new FlywayValidateException(result.errorDetails, result.getAllErrorMessages());
    }
    return "migrations "
        + result.invalidMigrations.stream()
            .map(m -> m.version != null ? m.version : m.description)
            .toList();
  }

  private static boolean onlyNotYetApplied(List<ValidateOutput> invalid) {
    return !invalid.isEmpty()
        && invalid.stream()
            .allMatch(m -> m.errorDetails != null && NOT_YET.contains(m.errorDetails.errorCode));
  }

  private static void sleep(Duration pause) {
    try {
      Thread.sleep(pause);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new FlywayException("Interrupted while waiting for the migrate step", e);
    }
  }

  /** The pause between two checks; a test passes one that advances its clock. */
  @FunctionalInterface
  public interface Sleeper {
    void sleep(Duration pause);
  }
}
