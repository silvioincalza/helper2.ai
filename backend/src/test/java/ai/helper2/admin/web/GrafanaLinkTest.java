package ai.helper2.admin.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** The Grafana link goes into an href: only an absolute http(s) URL with a host, or none. */
class GrafanaLinkTest {

    @Test
    void anHttpsLinkIsKeptAndAnEmptyOneIsNone() {
        assertThat(OverviewController.grafanaLink("https://grafana.example/d/helper2")).hasToString("https://grafana.example/d/helper2");
        assertThat(OverviewController.grafanaLink("  ")).isNull();
    }

    @Test
    void anyOtherSchemeOrNoHostStopsStartup() {
        assertThatThrownBy(() -> OverviewController.grafanaLink("javascript:alert(1)")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> OverviewController.grafanaLink("/d/helper2")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> OverviewController.grafanaLink("ftp://grafana.example")).isInstanceOf(IllegalStateException.class);
    }
}
