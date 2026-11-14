package ai.helper2.scout.config;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("helper2.retention")
public record ScoutRetentionProperties(@NotNull Duration scoutRuns) {}
