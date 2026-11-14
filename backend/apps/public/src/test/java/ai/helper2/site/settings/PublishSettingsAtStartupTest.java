package ai.helper2.site.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import ai.helper2.platform.config.BuildInfoProperties;
import ai.helper2.platform.settings.PublicAppSettings;
import ai.helper2.platform.settings.Setting;
import ai.helper2.platform.settings.SettingsGroup;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PublishSettingsAtStartupTest {

  static final Instant NOW = Instant.parse("2026-11-11T10:00:00Z");
  static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  static final BuildInfoProperties BUILD = new BuildInfoProperties("2026.11.1");
  static final SettingsGroup GUIDE =
      new SettingsGroup(
          "guide",
          "AI guide",
          SettingsGroup.GUIDE,
          List.of(Setting.count("guide.maxCandidates", "Results", 12, "results", null)));

  @Test
  void theGroupsAreWrittenWithTheBuildAndTheTime() {
    Recorded store = new Recorded();

    new PublishSettingsAtStartup(store, List.of(GUIDE), BUILD, CLOCK).ready();

    assertThat(store.newest())
        .contains(new PublicAppSettings.Publication("2026.11.1", NOW, List.of(GUIDE)));
  }

  @Test
  void aStoreThatCannotAnswerDoesNotStopTheApp() {
    PublicAppSettings down =
        new PublicAppSettings() {
          @Override
          public void publish(String build, Instant at, List<SettingsGroup> groups) {
            throw new IllegalStateException("no database");
          }

          @Override
          public Optional<Publication> newest() {
            return Optional.empty();
          }
        };

    assertThatCode(() -> new PublishSettingsAtStartup(down, List.of(GUIDE), BUILD, CLOCK).ready())
        .doesNotThrowAnyException();
  }

  static final class Recorded implements PublicAppSettings {
    private Publication publication;

    @Override
    public void publish(String build, Instant at, List<SettingsGroup> groups) {
      publication = new Publication(build, at, groups);
    }

    @Override
    public Optional<Publication> newest() {
      return Optional.ofNullable(publication);
    }
  }
}
