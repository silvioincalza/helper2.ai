package ai.helper2.monetisation;

import java.net.URI;
import java.util.Optional;

/**
 * What the site offers to fund itself, as configured (ADR 0057). An empty part is off: no link, no
 * script, no CSP origin.
 *
 * @param githubSponsors the GitHub Sponsors page the Support page links to
 * @param stripePayment the Stripe Payment Link the Support page links to
 * @param ga4MeasurementId the Google Analytics 4 stream, loaded only after the visitor accepts
 * @param ethicalAdsPublisher the EthicalAds publisher ID of the one ad slot
 */
public record Monetisation(
    Optional<URI> githubSponsors,
    Optional<URI> stripePayment,
    Optional<String> ga4MeasurementId,
    Optional<String> ethicalAdsPublisher) {

  public static final Monetisation OFF =
      new Monetisation(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

  public boolean analyticsOn() {
    return ga4MeasurementId.isPresent();
  }

  public boolean adsOn() {
    return ethicalAdsPublisher.isPresent();
  }
}
