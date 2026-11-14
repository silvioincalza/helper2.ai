package ai.helper2.config;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.testing.ConfigurationSnapshot;
import ai.helper2.testing.ResolvedConfiguration;
import ai.helper2.testing.SourcesOff;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

class SourcesOffCoverageTest {

  private static final Set<String> NOT_A_COLLECTOR = Set.of("page-reader");
  private static final Pattern SOURCE_SWITCH =
      Pattern.compile("helper2\\.sources\\.([^.]+)\\.enabled");

  @Test
  void sourcesOffSwitchesOffEverySourceTheAppConfigures() {
    List<String> off =
        List.of(SourcesOff.class.getAnnotation(TestPropertySource.class).properties());

    assertThat(configuredSources())
        .isNotEmpty()
        .filteredOn(id -> !NOT_A_COLLECTOR.contains(id))
        .allSatisfy(id -> assertThat(off).contains("helper2.sources." + id + ".enabled=false"));
  }

  private static Set<String> configuredSources() {
    return ResolvedConfiguration.ofClasspath(ConfigurationSnapshot.NO_PROFILE)
        .settings()
        .keySet()
        .stream()
        .map(SOURCE_SWITCH::matcher)
        .filter(Matcher::matches)
        .map(switchKey -> switchKey.group(1))
        .collect(Collectors.toSet());
  }
}
