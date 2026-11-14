package ai.helper2.search.config;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("helper2.retention")
public record SearchRetentionProperties(@NotNull Duration liveWebResults) {}
