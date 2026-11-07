package ai.helper2.catalog.config;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The Scout's retention periods for the pages it reads, from {@code helper2.scout.evidence}, for
 * the policy both apps state (ADR 0052, 0069). The back office's Scout binds the same keys.
 *
 * @param pageReadTtl how long a page a saved run read is not read again
 * @param pageTextTtl how long the cleaned text of a product's pages is kept for a retry
 */
@Validated
@ConfigurationProperties("helper2.scout.evidence")
public record ScoutEvidenceProperties(
    @NotNull Duration pageReadTtl, @NotNull Duration pageTextTtl) {}
