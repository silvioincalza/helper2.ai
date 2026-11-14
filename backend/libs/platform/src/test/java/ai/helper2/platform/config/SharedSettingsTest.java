package ai.helper2.platform.config;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.testing.ConfigurationSnapshot;
import ai.helper2.testing.ResolvedConfiguration;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;

class SharedSettingsTest {

  private static final String IMPORT = "spring.config.import";
  private static final String PROFILE = "spring.config.activate.on-profile";
  private static final String NO_PROFILE = "no profile";
  private static final String SHARED_FILE = "helper2-shared.yaml";
  private static final String PUBLIC_FILE = "apps/public/src/main/resources/application.yaml";
  private static final String BACK_OFFICE_FILE =
      "apps/backoffice/src/main/resources/application.yaml";

  @ParameterizedTest
  @ValueSource(strings = {PUBLIC_FILE, BACK_OFFICE_FILE})
  void anAppsFirstDocumentOnlyImportsTheSharedFileSoEveryValueBelowItWins(String appFile)
      throws IOException {
    assertThat(asMap(documents(new FileSystemResource(appFile)).getFirst()))
        .isEqualTo(Map.of(IMPORT, "classpath:" + SHARED_FILE));
  }

  @Test
  void aSettingBothAppsWriteWithTheSameValueBelongsInTheSharedFile() throws IOException {
    Map<String, String> publicSettings = ownSettings(PUBLIC_FILE);
    Map<String, String> backOfficeSettings = ownSettings(BACK_OFFICE_FILE);

    assertThat(publicSettings.entrySet())
        .filteredOn(setting -> setting.getValue().equals(backOfficeSettings.get(setting.getKey())))
        .isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {PUBLIC_FILE, BACK_OFFICE_FILE})
  void anAppNeverHidesTheValueASharedProfileDocumentSets(String appFile) throws IOException {
    Map<String, String> sharedProfileSettings = profileSettings(new ClassPathResource(SHARED_FILE));
    Map<String, String> appSettings = ownSettings(appFile);

    assertThat(sharedProfileSettings.keySet())
        .isNotEmpty()
        .allSatisfy(setting ->
            assertThat(appSettings)
                .doesNotContainKey(settingWithoutProfile(setting) + " (" + NO_PROFILE + ")"));
  }

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
  void bothAppsResolveTheSameValueForEverySharedSettingTheyDoNotOverride(String profiles)
      throws IOException {
    Map<String, String> publicApp = ResolvedConfiguration.ofFile(PUBLIC_FILE, profiles).settings();
    Map<String, String> backOffice =
        ResolvedConfiguration.ofFile(BACK_OFFICE_FILE, profiles).settings();

    assertThat(sharedKeysNoAppOverrides())
        .isNotEmpty()
        .allSatisfy(key -> assertThat(publicApp.get(key)).as(key).isEqualTo(backOffice.get(key)));
  }

  private static Set<String> sharedKeysNoAppOverrides() throws IOException {
    Set<String> keys = new HashSet<>();
    for (PropertySource<?> document : documents(new ClassPathResource(SHARED_FILE))) {
      keys.addAll(asMap(document).keySet());
    }
    keys.removeAll(keysOf(PUBLIC_FILE));
    keys.removeAll(keysOf(BACK_OFFICE_FILE));
    keys.remove(PROFILE);
    return keys;
  }

  private static Set<String> keysOf(String appFile) throws IOException {
    Set<String> keys = new HashSet<>();
    for (PropertySource<?> document : documents(new FileSystemResource(appFile))) {
      keys.addAll(asMap(document).keySet());
    }
    return keys;
  }

  private static Map<String, String> profileSettings(Resource yaml) throws IOException {
    Map<String, String> settings = settingsByProfile(yaml);
    settings.keySet().removeIf(setting -> setting.endsWith(" (" + NO_PROFILE + ")"));
    return settings;
  }

  private static String settingWithoutProfile(String setting) {
    return setting.substring(0, setting.lastIndexOf(" ("));
  }

  private static Map<String, String> ownSettings(String appFile) throws IOException {
    return settingsByProfile(new FileSystemResource(appFile));
  }

  private static Map<String, String> settingsByProfile(Resource yaml) throws IOException {
    Map<String, String> settings = new HashMap<>();
    for (PropertySource<?> document : documents(yaml)) {
      Map<String, String> written = asMap(document);
      String profile = written.getOrDefault(PROFILE, NO_PROFILE);
      written.remove(PROFILE);
      written.remove(IMPORT);
      written.forEach((key, value) -> settings.put(key + " (" + profile + ")", value));
    }
    return settings;
  }

  private static List<PropertySource<?>> documents(Resource yaml) throws IOException {
    return new YamlPropertySourceLoader().load(yaml.getDescription(), yaml);
  }

  private static Map<String, String> asMap(PropertySource<?> document) {
    Map<String, String> settings = new HashMap<>();
    for (String key : ((EnumerablePropertySource<?>) document).getPropertyNames()) {
      settings.put(key, String.valueOf(document.getProperty(key)));
    }
    return settings;
  }
}
