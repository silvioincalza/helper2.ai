package ai.helper2.catalog.config;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("helper2.scout.evidence")
public record ScoutEvidenceProperties(
    @NotNull Duration pageReadTtl, @NotNull Duration pageTextTtl) {}
