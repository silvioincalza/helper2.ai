package ai.helper2.monetisation.config;

import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Monetisation settings (ADR 0057). Each one empty means that part is off. Links must be https; the
 * IDs have the shape their vendor gives them, so a typo fails at startup, not in a visitor's
 * browser.
 *
 * @param githubSponsorsUrl {@code H2_SUPPORT_GITHUB_SPONSORS_URL}
 * @param stripePaymentUrl {@code H2_SUPPORT_STRIPE_PAYMENT_URL}
 * @param ga4MeasurementId {@code H2_ANALYTICS_GA4_MEASUREMENT_ID}, e.g. G-ABC123XYZ9
 * @param ethicalAdsPublisher {@code H2_ADS_ETHICALADS_PUBLISHER}
 */
@Validated
@ConfigurationProperties("helper2.monetisation")
public record MonetisationProperties(
    @Pattern(regexp = "^$|https://github\\.com/sponsors/[A-Za-z0-9-]+/?") String githubSponsorsUrl,
    @Pattern(regexp = "^$|https://(buy|donate)\\.stripe\\.com/[A-Za-z0-9_]+")
        String stripePaymentUrl,
    @Pattern(regexp = "^$|G-[A-Z0-9]{4,16}") String ga4MeasurementId,
    @Pattern(regexp = "^$|[a-z0-9-]{2,64}") String ethicalAdsPublisher) {}
