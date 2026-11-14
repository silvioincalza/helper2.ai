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

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SchemaCheck.SchemaProperties.class)
public class SchemaCheck {

  private static final Logger log = LoggerFactory.getLogger(SchemaCheck.class);

  private static final Set<ErrorCode> NOT_YET =
      Set.of(
          CoreErrorCode.RESOLVED_VERSIONED_MIGRATION_NOT_APPLIED,
          CoreErrorCode.RESOLVED_REPEATABLE_MIGRATION_NOT_APPLIED,
          CoreErrorCode.OUTDATED_REPEATABLE_MIGRATION);

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
                + ": did the migrate Job run? (ADR 0004)");
      }
      log.info("Waiting for the migrate step: {}", waitingFor);
      sleeper.sleep(schema.retryEvery());
    }
  }

  private static String check(Flyway flyway) {
    ValidateResult result;
    try {
      result = flyway.validateWithResult();
    } catch (FlywaySqlException e) {
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

  @FunctionalInterface
  public interface Sleeper {
    void sleep(Duration pause);
  }
}
