package ai.helper2.admin.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.admin.usecase.GetEffectiveSettings.App;
import ai.helper2.admin.usecase.GetEffectiveSettings.AppGroup;
import ai.helper2.admin.usecase.GetEffectiveSettings.EffectiveSettings;
import ai.helper2.platform.settings.PublicAppSettings;
import ai.helper2.platform.settings.PublicAppSettings.Publication;
import ai.helper2.platform.settings.Setting;
import ai.helper2.platform.settings.SettingsGroup;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class GetEffectiveSettingsTest {

  static final Instant AT = Instant.parse("2026-11-11T10:00:00Z");

  static final SettingsGroup SCOUT =
      new SettingsGroup(
          "scout",
          "Scout",
          SettingsGroup.SCOUT,
          List.of(Setting.flag("scout.on", "On", true, null)));
  static final SettingsGroup MODELS =
      new SettingsGroup("models", "Models", SettingsGroup.MODELS, List.of());
  static final SettingsGroup PUBLIC_MODELS =
      new SettingsGroup(
          "models",
          "Models",
          SettingsGroup.MODELS,
          List.of(Setting.model("models.fast", "Fast model", "nvidia/other", null)));
  static final SettingsGroup GUIDE =
      new SettingsGroup(
          "guide",
          "AI guide",
          SettingsGroup.GUIDE,
          List.of(Setting.count("guide.maxCandidates", "Results", 12, "results", null)));

  @Test
  void groupsAreInPageOrder() {
    EffectiveSettings settings =
        new GetEffectiveSettings(List.of(SCOUT, MODELS), published(GUIDE)).get();

    assertThat(settings.groups())
        .extracting(g -> g.group().id())
        .containsExactly("models", "guide", "scout");
  }

  @Test
  void aGroupOnlyThePublicAppHasComesFromWhatItPublished() {
    EffectiveSettings settings =
        new GetEffectiveSettings(List.of(SCOUT, MODELS), published(GUIDE)).get();

    assertThat(settings.groups())
        .contains(new AppGroup(App.PUBLIC, GUIDE), new AppGroup(App.BACK_OFFICE, SCOUT));
    assertThat(settings.publicApp()).map(Publication::build).contains("2026.11.1");
  }

  @Test
  void aGroupBothAppsHaveIsTheBackOfficesOwn() {
    EffectiveSettings settings =
        new GetEffectiveSettings(List.of(MODELS), published(PUBLIC_MODELS, GUIDE)).get();

    assertThat(settings.groups())
        .containsExactly(new AppGroup(App.BACK_OFFICE, MODELS), new AppGroup(App.PUBLIC, GUIDE));
  }

  @Test
  void beforeThePublicAppsFirstStartOnlyTheBackOfficesGroupsShow() {
    EffectiveSettings settings = new GetEffectiveSettings(List.of(SCOUT), nothingPublished()).get();

    assertThat(settings.groups()).containsExactly(new AppGroup(App.BACK_OFFICE, SCOUT));
    assertThat(settings.publicApp()).isEmpty();
  }

  static PublicAppSettings published(SettingsGroup... groups) {
    return new FixedPublication(Optional.of(new Publication("2026.11.1", AT, List.of(groups))));
  }

  static PublicAppSettings nothingPublished() {
    return new FixedPublication(Optional.empty());
  }

  record FixedPublication(Optional<Publication> newest) implements PublicAppSettings {
    @Override
    public void publish(String build, Instant at, List<SettingsGroup> groups) {
      throw new UnsupportedOperationException();
    }
  }
}
