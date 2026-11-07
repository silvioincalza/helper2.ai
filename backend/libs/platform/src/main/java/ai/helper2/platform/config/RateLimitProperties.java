package ai.helper2.platform.config;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Requests per client address, from {@code helper2.rate-limit}; past them the answer is 429. */
@Validated
@ConfigurationProperties("helper2.rate-limit")
public record RateLimitProperties(
    @Positive int searchPerMinute,
    @Positive int reportPerHour,
    @Positive int guidePerMinute,
    @Positive int recentPerMinute) {}
