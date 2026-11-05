package ai.helper2.admin.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.platform.settings.Setting;
import ai.helper2.platform.settings.SettingsGroup;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The groups come in page order, whatever order the modules were built in. */
class GetEffectiveSettingsTest {

  @Test
  void groupsAreInPageOrder() {
    SettingsGroup scout =
        new SettingsGroup(
            "scout",
            "Scout",
            SettingsGroup.SCOUT,
            List.of(Setting.flag("scout.on", "On", true, null)));
    SettingsGroup models = new SettingsGroup("models", "Models", SettingsGroup.MODELS, List.of());

    assertThat(new GetEffectiveSettings(List.of(scout, models)).get())
        .extracting(SettingsGroup::id)
        .containsExactly("models", "scout");
  }
}
