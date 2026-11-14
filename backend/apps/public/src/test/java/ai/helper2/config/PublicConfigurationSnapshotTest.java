package ai.helper2.config;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.testing.ConfigurationSnapshot;
import java.nio.file.Path;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PublicConfigurationSnapshotTest {

  private final ConfigurationSnapshot snapshot =
      new ConfigurationSnapshot(Path.of("apps/public/src/test/resources/configuration"));

  @ParameterizedTest
  @ValueSource(
      strings = {
        ConfigurationSnapshot.NO_PROFILE,
        "dev",
        "oauth-github",
        "llm-nebius",
        "demo",
        ConfigurationSnapshot.EVERY_PROFILE
      })
  void everySettingOfTheYamlResolvesAsRecorded(String profiles) {
    assertThat(snapshot.now(profiles)).containsExactlyElementsOf(snapshot.recorded(profiles));
  }
}
