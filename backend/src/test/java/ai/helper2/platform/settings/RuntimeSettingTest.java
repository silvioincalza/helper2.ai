package ai.helper2.platform.settings;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** The allow-list of runtime settings (ADR 0060): what may change, and within which bounds. */
class RuntimeSettingTest {

  @Test
  void onlyTheSixSpendingLimitsMayChange() {
    assertThat(Arrays.stream(RuntimeSetting.values()).map(RuntimeSetting::key))
        .containsExactly(
            "scout.maxEnrichmentsPerRun",
            "scout.dailyEnrichmentCalls",
            "scout.gapDailyCalls",
            "guide.dailyModelCalls",
            "search.dailyTranslations",
            "search.webGlobalDailyCredits");
  }

  @ParameterizedTest
  @EnumSource(RuntimeSetting.class)
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
    assertThat(setting.variable()).startsWith("H2_");
    assertThat(setting.label()).isNotBlank();
    assertThat(setting.unit()).isNotBlank();
    assertThat(setting.help()).contains("0");
  }

  @Test
  void aKeyOutsideTheAllowListIsUnknown() {
    assertThat(RuntimeSetting.byKey("models.reasoning")).isEmpty();
    assertThat(RuntimeSetting.byKey("guide.clientDailyQuestions")).isEmpty();
  }
}
