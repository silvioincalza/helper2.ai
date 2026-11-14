package ai.helper2.admin.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.helper2.platform.BadRequestException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RuntimeSettingReasonTest {

  @Test
  void aPlainReasonIsTrimmedAndKeepsItsSpaces() {
    assertThat(ChangeRuntimeSetting.Reasons.clean("  load  test ")).isEqualTo("load  test");
    assertThat(ChangeRuntimeSetting.Reasons.clean("   ")).isNull();
    assertThat(ChangeRuntimeSetting.Reasons.clean(null)).isNull();
  }

  @ParameterizedTest
  @ValueSource(strings = {"two\nlines", "a\ttab", "zero​width", "right‮to left", "bom﻿"})
  void aControlOrFormatCharacterIsRefused(String reason) {
    assertThatThrownBy(() -> ChangeRuntimeSetting.Reasons.clean(reason))
        .isInstanceOf(BadRequestException.class);
  }

  @Test
  void aReasonLongerThanTheDatabaseAllowsIsRefusedAsABadRequestNotAServerError() {
    String longest = "x".repeat(ChangeRuntimeSetting.Reasons.MAX_CHARS);
    assertThat(ChangeRuntimeSetting.Reasons.clean(" " + longest + " ")).isEqualTo(longest);
    assertThatThrownBy(() -> ChangeRuntimeSetting.Reasons.clean(longest + "x"))
        .isInstanceOf(BadRequestException.class)
        .hasMessageContaining("500");
  }
}
