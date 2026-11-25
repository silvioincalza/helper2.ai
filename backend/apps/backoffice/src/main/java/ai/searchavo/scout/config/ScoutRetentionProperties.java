package ai.searchavo.scout.config;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("searchavo.retention")
public record ScoutRetentionProperties(@NotNull Duration scoutRuns) {}
