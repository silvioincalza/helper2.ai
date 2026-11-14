package ai.helper2.admin.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.helper2.admin.config.AdminProperties;
import ai.helper2.testing.ConfigBinding;
import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@ExtendWith(OutputCaptureExtension.class)
class GrafanaLinkTest {

  static final String SECRET = "fakeGrafanaSecret7c";

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(AdminProperties.class)
  static class OverviewLink {

    @Bean
    OverviewController overviewController(AdminProperties admin) {
      return new OverviewController(null, admin);
    }
  }

  @Test
  void anHttpsLinkIsKeptAndAnEmptyOneIsNone() {
    assertThat(OverviewController.grafanaLink("https://grafana.example/d/helper2"))
        .hasToString("https://grafana.example/d/helper2");
    assertThat(OverviewController.grafanaLink("  ")).isNull();
  }

  @Test
  void anyOtherSchemeOrNoHostStopsStartup() {
    assertThatThrownBy(() -> OverviewController.grafanaLink("javascript:alert(1)"))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> OverviewController.grafanaLink("/d/helper2"))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> OverviewController.grafanaLink("ftp://grafana.example"))
        .isInstanceOf(IllegalStateException.class);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "https://viewer:" + SECRET + "@grafana.example/d/helper2",
        "https://" + SECRET + "@grafana.example/d/helper2",
        "https://grafana.example/d/helper2?auth_token=" + SECRET,
        "https://grafana.example/d/helper2?",
        "https://grafana.example/d/helper2#" + SECRET,
        "https://grafana.example/d/ " + SECRET,
        "https://grafana.example:" + SECRET + "/d/helper2",
        "https://[" + SECRET + "/d/helper2",
        "ftp://viewer:" + SECRET + "@grafana.example",
      })
  void aLinkWithUserInfoAQueryAFragmentOrAMalformedOneIsRefusedWithoutRepeatingIt(String value) {
    assertThatThrownBy(() -> OverviewController.grafanaLink(value))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("H2_ADMIN_GRAFANA_URL")
        .satisfies(refusal -> assertThat(everythingPrintedOf(refusal)).doesNotContain(SECRET));
  }

  @Test
  void theAppStartsWithAPlainLink() {
    ConfigBinding.of(OverviewLink.class)
        .withSystemProperties("H2_ADMIN_GRAFANA_URL=https://grafana.example/d/helper2")
        .run(context -> assertThat(context).hasNotFailed());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "https://viewer:" + SECRET + "@grafana.example/d/helper2",
        "https://grafana.example/d/helper2?auth_token=" + SECRET,
        "https://grafana.example/d/helper2#" + SECRET,
        "https://grafana.example/d/ " + SECRET,
      })
  void theAppDoesNotStartAndNeitherTheFailureNorTheLogRepeatsTheLink(
      String value, CapturedOutput output) {
    ConfigBinding.of(OverviewLink.class)
        .withSystemProperties("H2_ADMIN_GRAFANA_URL=" + value)
        .run(context -> {
          assertThat(context).hasFailed();
          assertThat(everythingPrintedOf(context.getStartupFailure()))
              .contains("H2_ADMIN_GRAFANA_URL")
              .doesNotContain(SECRET);
        });
    assertThat(output.getAll()).doesNotContain(SECRET);
  }

  private static String everythingPrintedOf(Throwable failure) {
    StringWriter printed = new StringWriter();
    failure.printStackTrace(new PrintWriter(printed));
    return printed.toString();
  }
}
