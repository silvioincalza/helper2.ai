package ai.helper2.scout.config;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The Scout's retention period (ADR 0034), from the shared {@code helper2.retention} prefix. Each
 * module binds only its own periods there.
 *
 * @param scoutRuns how long Scout run records are kept
 */
@Validated
@ConfigurationProperties("helper2.retention")
public record ScoutRetentionProperties(@NotNull Duration scoutRuns) {}
