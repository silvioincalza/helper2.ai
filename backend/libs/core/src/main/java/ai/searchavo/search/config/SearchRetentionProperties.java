package ai.searchavo.search.config;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("searchavo.retention")
public record SearchRetentionProperties(@NotNull Duration liveWebResults) {}
