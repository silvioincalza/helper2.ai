package ai.searchavo.platform.config;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("searchavo.rate-limit")
public record RateLimitProperties(
    @Positive int searchPerMinute,
    @Positive int reportPerHour,
    @Positive int guidePerMinute,
    @Positive int recentPerMinute,
    @Positive int defaultPerMinute) {}
