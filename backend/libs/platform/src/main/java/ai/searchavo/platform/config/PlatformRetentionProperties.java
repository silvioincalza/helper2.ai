package ai.searchavo.platform.config;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("searchavo.retention")
public record PlatformRetentionProperties(
    @NotNull Duration runtimeSettings, @NotNull Duration backups) {}
