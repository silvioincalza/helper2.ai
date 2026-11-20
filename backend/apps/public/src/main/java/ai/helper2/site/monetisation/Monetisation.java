package ai.helper2.site.monetisation;

import java.net.URI;
import java.util.Optional;

public record Monetisation(
    Optional<URI> githubSponsors,
    Optional<URI> stripePayment,
    Optional<String> ga4MeasurementId,
    Optional<String> adsenseClient,
    Optional<String> adsenseSlot,
    Optional<String> adsenseSlotSearch,
    Optional<String> adsenseSlotGuide) {

  public static final Monetisation OFF =
      new Monetisation(
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          Optional.empty());

  public boolean analyticsOn() {
    return ga4MeasurementId.isPresent();
  }

  public boolean adsenseOn() {
    return adsenseClient.isPresent()
        && (adsenseSlot.isPresent()
            || adsenseSlotSearch.isPresent()
            || adsenseSlotGuide.isPresent());
  }

  public Optional<String> adsTxt() {
    return adsenseClient.map(
        c -> "google.com, " + c.substring("ca-".length()) + ", DIRECT, f08c47fec0942fa0");
  }
}
