package ai.searchavo.search.config;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("searchavo.vitality")
public record VitalityProperties(
    @PositiveOrZero long hostedAppMinLikes,
    @PositiveOrZero long forkMinStars,
    @NotNull Duration maintainedWithin,
    @PositiveOrZero long establishedStars) {}
