package ai.helper2.search.config;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The search module's retention period (ADR 0034), from the shared {@code helper2.retention}
 * prefix. Each module binds only its own periods there.
 *
 * @param liveWebResults how long stored Tavily result sets are kept after they were fetched
 */
@Validated
@ConfigurationProperties("helper2.retention")
record SearchRetentionProperties(@NotNull Duration liveWebResults) {}
