package ai.helper2.admin.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The back office, from {@code helper2.admin}.
 *
 * @param grafanaUrl the metrics dashboard the overview links to; empty = no link. The overview
 *     checks it is an absolute http(s) URL.
 * @param publicBaseUrl the public site (H2_PUBLIC_BASE_URL): a resource link on the admin host goes
 *     there
 * @param guideDailyModelCalls the guide's daily model-call cap (H2_GUIDE_DAILY_MODEL_CALLS), as the
 *     public app runs it: its default on the Settings page (ADR 0060)
 */
@Validated
@ConfigurationProperties("helper2.admin")
public record AdminProperties(
    @NotNull String grafanaUrl,
    @NotBlank String publicBaseUrl,
    @PositiveOrZero int guideDailyModelCalls) {}
