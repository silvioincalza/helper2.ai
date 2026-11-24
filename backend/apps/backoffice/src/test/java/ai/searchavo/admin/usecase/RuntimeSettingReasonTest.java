package ai.searchavo.admin.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RuntimeSettingReasonTest {

  @Test
  void aPlainReasonIsTrimmedAndKeepsItsSpaces() {
    assertThat(ChangeRuntimeSetting.Reasons.isPlain("  load  test ")).isTrue();
    assertThat(ChangeRuntimeSetting.Reasons.clean("  load  test ")).isEqualTo("load  test");
    assertThat(ChangeRuntimeSetting.Reasons.clean("   ")).isNull();
    assertThat(ChangeRuntimeSetting.Reasons.clean(null)).isNull();
    assertThat(ChangeRuntimeSetting.Reasons.isPlain(null)).isTrue();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"two\nlines", "a\ttab", "zero\u200bwidth", "right\u202eto left", "bom\ufeff"})
  void aControlOrFormatCharacterIsNotPlain(String reason) {
    assertThat(ChangeRuntimeSetting.Reasons.isPlain(reason)).isFalse();
  }

  @Test
  void aReasonAsLongAsTheContractAllowsIsKeptWithoutTheSpacesAroundIt() {
    String longest = "x".repeat(498);
    assertThat(ChangeRuntimeSetting.Reasons.clean(" " + longest + " ")).isEqualTo(longest);
  }
}
