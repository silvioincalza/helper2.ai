package ai.searchavo.site.monetisation;

import java.util.function.BooleanSupplier;

public final class CurrentMonetisation {

  private final Monetisation withAds;
  private final Monetisation withoutAds;
  private final BooleanSupplier adsEnabled;

  public CurrentMonetisation(Monetisation configured, BooleanSupplier adsEnabled) {
    this.withAds = configured;
    this.withoutAds = configured.withoutAdUnits();
    this.adsEnabled = adsEnabled;
  }

  public Monetisation now() {
    return adsEnabled.getAsBoolean() ? withAds : withoutAds;
  }
}
