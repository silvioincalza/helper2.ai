package ai.helper2.identity.config;

import ai.helper2.identity.RetentionPeriods;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Identity's retention periods (ADR 0035), from the shared {@code helper2.retention} prefix. Each
 * module binds only its own periods there.
 */
@Validated
@ConfigurationProperties("helper2.retention")
public record IdentityRetentionProperties(
    @NotNull Duration inactiveAccounts, @NotNull Duration adminAudit) implements RetentionPeriods {}
