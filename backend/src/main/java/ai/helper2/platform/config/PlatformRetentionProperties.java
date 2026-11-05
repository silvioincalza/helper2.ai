package ai.helper2.platform.config;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The platform's retention periods (ADR 0034), from the shared {@code helper2.retention} prefix.
 * Each module binds only its own periods there.
 *
 * @param runtimeSettings how long a runtime setting change is kept (ADR 0058); each setting's
 *     newest change stays
 */
@Validated
@ConfigurationProperties("helper2.retention")
public record PlatformRetentionProperties(@NotNull Duration runtimeSettings) {}
