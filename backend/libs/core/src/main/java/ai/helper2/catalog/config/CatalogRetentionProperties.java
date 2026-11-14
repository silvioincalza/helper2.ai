package ai.helper2.catalog.config;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("helper2.retention")
public record CatalogRetentionProperties(
    @NotNull Duration resolvedReviewItems,
    @NotNull Duration resolvedIssueReports,
    @NotNull Duration enrichmentRuns,
    @NotNull Duration webNews,
    @NotNull Duration pastEvents,
    @NotNull Duration observedUnseen,
    @NotNull Duration listIndex) {}
