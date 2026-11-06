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
 * @param adsenseClient the Google AdSense publisher ({@code ca-pub-...}, ADR 0059): the site
 *     verification tag and ads.txt
 */
public record Monetisation(
    Optional<URI> githubSponsors,
    Optional<URI> stripePayment,
    Optional<String> ga4MeasurementId,
    Optional<String> ethicalAdsPublisher,
    Optional<String> adsenseClient) {

  public static final Monetisation OFF =
      new Monetisation(
          Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

  public boolean analyticsOn() {
    return ga4MeasurementId.isPresent();
  }

  public boolean adsOn() {
    return ethicalAdsPublisher.isPresent();
  }

  /**
   * The ads.txt line that names Google as an authorised seller for this publisher (IAB ads.txt;
   * f08c47fec0942fa0 is Google's certification authority ID). Empty while AdSense is off.
   */
  public Optional<String> adsTxt() {
    return adsenseClient.map(
        c -> "google.com, " + c.substring("ca-".length()) + ", DIRECT, f08c47fec0942fa0");
  }
}
