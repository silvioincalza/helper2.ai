package ai.searchavo.platform.settings;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class RuntimeSettingTest {

  // UC-028 BR-001: the runtime limits and the switch.
  @Test
  void onlyTheNineSpendingLimitsAndTheAdsSwitchMayChange() {
    assertThat(Arrays.stream(RuntimeSetting.values()).map(RuntimeSetting::key))
        .containsExactly(
            "scout.maxEnrichmentsPerRun",
            "scout.dailyEnrichmentCalls",
            "scout.gapDailyCalls",
            "guide.dailyModelCalls",
            "search.dailyTranslations",
            "search.webGlobalDailyCredits",
            "scoutSources.dailyCredits",
            "scout.evidenceDailyExtractCredits",
            "research.dailyModelCalls",
            "ads.enabled");
  }

  @ParameterizedTest
  @EnumSource(
      value = RuntimeSetting.class,
      names = {"ADS_ENABLED"},
      mode = EnumSource.Mode.EXCLUDE)
  void zeroToTheCodeMaximumIsAllowedAndNothingOutside(RuntimeSetting setting) {
    assertThat(setting.allows(0)).as("0 turns the use off").isTrue();
    assertThat(setting.allows(setting.max())).isTrue();
    assertThat(setting.allows(-1)).isFalse();
    assertThat(setting.allows(setting.max() + 1)).as("more needs a pull request").isFalse();
    assertThat(setting.max()).isPositive().isLessThanOrEqualTo(Integer.MAX_VALUE);
  }

  @ParameterizedTest
  @EnumSource(RuntimeSetting.class)
  void eachSettingHasItsWordsAndItsVariable(RuntimeSetting setting) {
    assertThat(RuntimeSetting.byKey(setting.key())).contains(setting);
    assertThat(setting.variable()).startsWith("SA_");
    assertThat(setting.label()).isNotBlank();
    if (setting.isSwitch()) {
      assertThat(setting.help()).contains("On:").contains("Off:");
    } else {
      assertThat(setting.unit()).isNotBlank();
      assertThat(setting.help()).contains("0");
    }
    assertThat(setting.label() + " " + setting.help())
        .as("the product is searchavo.ai, in lower case and in full")
        .doesNotContainPattern("Searchavo|searchavo(?!\\.ai)");
  }

  // UC-028 BR-001: each of the three credit limits stops at 3,000 a day.
  @Test
  void theCreditLimitsStopAtThreeThousandADay() {
    assertThat(RuntimeSetting.SEARCH_WEB_GLOBAL_DAILY_CREDITS.max()).isEqualTo(3_000);
    assertThat(RuntimeSetting.SCOUT_SEARCH_DAILY_CREDITS.max()).isEqualTo(3_000);
    assertThat(RuntimeSetting.SCOUT_PAGE_READ_DAILY_CREDITS.max()).isEqualTo(3_000);
    assertThat(RuntimeSetting.SCOUT_SEARCH_DAILY_CREDITS.variable())
        .isEqualTo("SA_SCOUT_DAILY_CREDITS");
    assertThat(RuntimeSetting.SCOUT_PAGE_READ_DAILY_CREDITS.variable())
        .isEqualTo("SA_SCOUT_PAGE_READER_DAILY_CREDITS");
  }

  @Test
  void theAdsSwitchIsTrueOrFalse() {
    RuntimeSetting ads = RuntimeSetting.ADS_ENABLED;

    assertThat(ads.isSwitch()).isTrue();
    assertThat(ads.allows(0)).isTrue();
    assertThat(ads.allows(1)).isTrue();
    assertThat(ads.allows(2)).isFalse();
    assertThat(ads.shown(1L)).isEqualTo("true");
    assertThat(ads.shown(0L)).isEqualTo("false");
    assertThat(RuntimeSetting.SCOUT_GAP_DAILY_CALLS.shown(7L)).isEqualTo("7");
    assertThat(RuntimeSetting.SCOUT_GAP_DAILY_CALLS.isSwitch()).isFalse();
  }

  @Test
  void aKeyOutsideTheAllowListIsUnknown() {
    assertThat(RuntimeSetting.byKey("models.reasoning")).isEmpty();
    assertThat(RuntimeSetting.byKey("guide.clientDailyQuestions")).isEmpty();
  }
}
