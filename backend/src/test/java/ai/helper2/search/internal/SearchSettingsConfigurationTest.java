package ai.helper2.search.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.platform.settings.Setting;
import ai.helper2.search.config.SearchProperties;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The Settings page shows what search does, not only what a property asks for (ADR 0045). */
class SearchSettingsConfigurationTest {

  private static final SearchProperties TRANSLATION_ON =
      new SearchProperties(
          Duration.ofHours(24),
          30,
          Duration.ofMillis(2500),
          new SearchProperties.Translation(true, 300, Duration.ofHours(24)),
          new SearchProperties.Web(
              Duration.ofHours(24),
              Duration.ofHours(48),
              new SearchProperties.Quota(
                  new SearchProperties.Limits(3, 3),
                  new SearchProperties.Limits(10, 10),
                  false,
                  null)));

  @Test
  void translationIsOffWhenNoTranslatorRunsEvenIfThePropertyIsOn() {
    assertThat(translationOn(false, null)).isEqualTo("false");
  }

  @Test
  void translationIsOnWhenATranslatorRuns() {
    assertThat(translationOn(true, "nvidia/nemotron-fast")).isEqualTo("true");
  }

  private static String translationOn(boolean translating, String model) {
    return SearchSettingsConfiguration.searchSettingsGroup(
            TRANSLATION_ON, translating, model, null, List.of("github"))
        .settings()
        .stream()
        .filter(s -> s.key().equals("search.translationOn"))
        .map(Setting::value)
        .findFirst()
        .orElseThrow();
  }
}
