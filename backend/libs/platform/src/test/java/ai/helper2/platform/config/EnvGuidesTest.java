package ai.helper2.platform.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class EnvGuidesTest {

  private static final List<Path> SETTINGS =
      List.of(
          Path.of("libs/platform/src/main/resources/helper2-shared.yaml"),
          Path.of("apps/public/src/main/resources/application.yaml"),
          Path.of("apps/backoffice/src/main/resources/application.yaml"));
  private static final Path LOCAL_GUIDE = Path.of("../.env.example");
  private static final Path CLUSTER_GUIDE = Path.of("../deploy/k8s/app.env.example");
  private static final Path CHART_TEMPLATES = Path.of("../deploy/helm/helper2/templates");

  private static final Pattern READ_BY_AN_APP = Pattern.compile("\\$\\{(H2_[A-Z0-9_]+)");
  private static final Pattern GUIDE_KEY =
      Pattern.compile("^#? ?(H2_[A-Z0-9_]+)=", Pattern.MULTILINE);
  private static final Pattern NAME = Pattern.compile("\\b(H2_[A-Z0-9_]+)\\b");

  private static final Map<String, String> NOT_IN_THE_LOCAL_GUIDE =
      Map.of(
          "H2_PROFILES",
          "the chart sets it from app.springProfiles; a local run names its profiles on the command"
              + " line");

  private static final Map<String, String> IN_A_GUIDE_AND_READ_BY_NO_APP =
      Map.of(
          "H2_REDIS_PUBLIC_PASSWORD",
          "the chart gives it to the public app as H2_REDIS_PASSWORD",
          "H2_REDIS_BACKOFFICE_PASSWORD",
          "the chart gives it to the back office as H2_REDIS_PASSWORD",
          "H2_REDIS_MONITOR_PASSWORD",
          "the chart gives it to the Redis exporter");

  @Test
  void theLocalGuideNamesEveryVariableTheAppsRead() {
    Set<String> missing = readByAnApp();
    missing.removeAll(keysOf(LOCAL_GUIDE));
    missing.removeAll(NOT_IN_THE_LOCAL_GUIDE.keySet());

    assertThat(missing).as("read by an app, not in .env.example").isEmpty();
  }

  @Test
  void theClusterGuideNamesEveryVariableTheAppsReadAndTheChartDoesNotSet() {
    Set<String> missing = readByAnApp();
    missing.removeAll(keysOf(CLUSTER_GUIDE));
    missing.removeAll(setByTheChart());

    assertThat(missing)
        .as("read by an app, neither in deploy/k8s/app.env.example nor set by the chart")
        .isEmpty();
  }

  @Test
  void aGuideNamesOnlyVariablesAnAppReads() {
    Set<String> unread = keysOf(LOCAL_GUIDE);
    unread.addAll(keysOf(CLUSTER_GUIDE));
    unread.removeAll(readByAnApp());
    unread.removeAll(IN_A_GUIDE_AND_READ_BY_NO_APP.keySet());

    assertThat(unread).as("in an env guide, read by no app").isEmpty();
  }

  @Test
  void everyNamedExceptionStillHolds() {
    assertThat(readByAnApp())
        .containsAll(NOT_IN_THE_LOCAL_GUIDE.keySet())
        .doesNotContainAnyElementsOf(IN_A_GUIDE_AND_READ_BY_NO_APP.keySet());
    assertThat(keysOf(LOCAL_GUIDE)).doesNotContainAnyElementsOf(NOT_IN_THE_LOCAL_GUIDE.keySet());
    assertThat(keysOf(CLUSTER_GUIDE)).containsAll(IN_A_GUIDE_AND_READ_BY_NO_APP.keySet());
  }

  private static Set<String> readByAnApp() {
    Set<String> names = new TreeSet<>();
    SETTINGS.forEach(file -> names.addAll(matches(READ_BY_AN_APP, file)));
    return names;
  }

  private static Set<String> keysOf(Path guide) {
    return matches(GUIDE_KEY, guide);
  }

  private static Set<String> setByTheChart() {
    Set<String> names = new TreeSet<>();
    try (Stream<Path> templates = Files.walk(CHART_TEMPLATES)) {
      templates.filter(Files::isRegularFile).forEach(file -> names.addAll(matches(NAME, file)));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return names;
  }

  private static Set<String> matches(Pattern pattern, Path file) {
    Set<String> names = new TreeSet<>();
    try {
      Matcher matcher = pattern.matcher(Files.readString(file));
      while (matcher.find()) {
        names.add(matcher.group(1));
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return names;
  }
}
