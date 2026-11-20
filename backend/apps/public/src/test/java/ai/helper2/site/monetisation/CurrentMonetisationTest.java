package ai.helper2.site.monetisation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class CurrentMonetisationTest {

  private static final Monetisation CONFIGURED =
      new Monetisation(
          Optional.empty(),
          Optional.empty(),
          Optional.of("G-TEST1234"),
          Optional.of("ca-pub-4688630163076563"),
          Optional.of("1234567890"),
          Optional.of("2345678901"),
          Optional.of("3456789012"));

  @Test
  void theAdUnitsShowOnlyWhileTheSwitchIsOn() {
    AtomicBoolean adsEnabled = new AtomicBoolean(false);
    CurrentMonetisation current = new CurrentMonetisation(CONFIGURED, adsEnabled::get);

    assertThat(current.now().adsenseOn()).isFalse();
    assertThat(current.now().adsenseSlot()).isEmpty();
    assertThat(current.now().adsenseSlotSearch()).isEmpty();
    assertThat(current.now().adsenseSlotGuide()).isEmpty();

    adsEnabled.set(true);

    assertThat(current.now()).isEqualTo(CONFIGURED);
    assertThat(current.now().adsenseOn()).isTrue();

    adsEnabled.set(false);

    assertThat(current.now().adsenseOn()).isFalse();
  }

  @Test
  void theSiteCheckAndTheOtherPartsStayWhileTheSwitchIsOff() {
    Monetisation off = new CurrentMonetisation(CONFIGURED, () -> false).now();

    assertThat(off.adsTxt()).isPresent();
    assertThat(off.adsenseClient()).contains("ca-pub-4688630163076563");
    assertThat(off.analyticsOn()).isTrue();
  }

  @Test
  void theSwitchAloneShowsNoAdWithoutAnAdUnit() {
    Monetisation publisherOnly = CONFIGURED.withoutAdUnits();

    assertThat(new CurrentMonetisation(publisherOnly, () -> true).now().adsenseOn()).isFalse();
  }
}
