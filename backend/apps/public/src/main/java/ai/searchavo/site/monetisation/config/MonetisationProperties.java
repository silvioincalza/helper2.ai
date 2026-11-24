package ai.searchavo.site.monetisation.config;

import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("searchavo.monetisation")
public record MonetisationProperties(
    @Pattern(regexp = "^$|https://github\\.com/sponsors/[A-Za-z0-9-]+/?") String githubSponsorsUrl,
    @Pattern(regexp = "^$|https://(buy|donate)\\.stripe\\.com/[A-Za-z0-9_]+")
        String stripePaymentUrl,
    @Pattern(regexp = "^$|G-[A-Z0-9]{4,16}") String ga4MeasurementId,
    @Pattern(regexp = "^$|ca-pub-[0-9]{16}") String adsenseClient,
    @Pattern(regexp = "^$|[0-9]{8,12}") String adsenseSlot,
    @Pattern(regexp = "^$|[0-9]{8,12}") String adsenseSlotSearch,
    @Pattern(regexp = "^$|[0-9]{8,12}") String adsenseSlotGuide,
    boolean adsEnabled) {}
