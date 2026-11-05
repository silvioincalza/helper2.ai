package ai.helper2.platform.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A setting shows plain values only; a credential cannot be shown, whatever a module passes. */
class SettingTest {

  @Test
  void eachKindWritesItsValueTheWayThePageReadsIt() {
    assertThat(Setting.count("a.b", "B", 500, "calls a day", "H2_B").value()).isEqualTo("500");
    assertThat(Setting.cap("a.b", "B", null, "credits a day", "H2_B").value())
        .as("no cap")
        .isNull();
    assertThat(Setting.flag("a.b", "B", true, null).value()).isEqualTo("true");
    assertThat(Setting.duration("a.b", "B", Duration.ofHours(24), null).value()).isEqualTo("86400");
    assertThat(Setting.model("a.b", "B", " none ", null).value()).as("no model").isNull();
    assertThat(Setting.model("a.b", "B", "nvidia/nemotron-3-super-120b-a12b", null).value())
        .isEqualTo("nvidia/nemotron-3-super-120b-a12b");
    assertThat(Setting.version("a.b", "B", "").value()).isNull();
    assertThat(Setting.schedule("a.b", "B", "0 7 3 * * *", "H2_B").value())
        .isEqualTo("0 7 3 * * *");
    assertThat(Setting.names("a.b", "B", List.of("npm", "arxiv"), null).value())
        .as("sorted")
        .isEqualTo("arxiv,npm");
  }

  @Test
  void aUrlWithCredentialsOrAQuotedValueIsRefused() {
    assertThatThrownBy(
            () -> Setting.model("a.b", "B", "https://user:secret@token.example/v1", null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining("secret");
    assertThatThrownBy(() -> Setting.model("a.b", "B", "\"x\"", null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Setting.model("a.b", "B", "x".repeat(Setting.MAX_LENGTH + 1), null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aGroupTakesOnlyItsOwnKeysOnce() {
    Setting own = Setting.flag("scout.on", "On", true, null);
    assertThat(new SettingsGroup("scout", "Scout", 1, List.of(own)).settings()).hasSize(1);
    assertThatThrownBy(() ->
            new SettingsGroup(
                "scout", "Scout", 1, List.of(Setting.flag("guide.on", "On", true, null))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new SettingsGroup("scout", "Scout", 1, List.of(own, own)))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
