package ai.searchavo.identity.security;

import static org.assertj.core.api.Assertions.assertThat;

import ai.searchavo.identity.config.IdentityProperties;
import ai.searchavo.identity.config.IdentityRetentionProperties;
import ai.searchavo.testing.ConfigBinding;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class IdentityPropertiesBindingTest {

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties({IdentityProperties.class, IdentityRetentionProperties.class})
  static class Binding {}

  final ApplicationContextRunner context = ConfigBinding.of(Binding.class);

  @Test
  void theYamlDefaultsBind() {
    context.run(c -> {
      assertThat(c.getBean(IdentityProperties.class))
          .isEqualTo(new IdentityProperties("/", List.of(), false, false));
      assertThat(c.getBean(IdentityRetentionProperties.class))
          .isEqualTo(new IdentityRetentionProperties(Duration.ofDays(730), Duration.ofDays(730)));
    });
  }

  @Test
  void theDevProfileReturnsToTheViteServerAndOpensTheMetrics() {
    context
        .withPropertyValues("spring.profiles.active=dev")
        .run(c -> {
          IdentityProperties identity = c.getBean(IdentityProperties.class);
          assertThat(identity.frontendUrl()).isEqualTo("http://localhost:5173/");
          assertThat(identity.metricsPublic()).isTrue();
        });
  }

  @Test
  void anH2VariableReachesItsField() {
    context
        .withSystemProperties("SA_NOINDEX=true", "SA_RETENTION_ADMIN_AUDIT=P1000D")
        .run(c -> {
          IdentityProperties identity = c.getBean(IdentityProperties.class);
          assertThat(identity.noindex()).isTrue();
          assertThat(c.getBean(IdentityRetentionProperties.class).adminAudit())
              .isEqualTo(Duration.ofDays(1000));
        });
  }

  @Test
  void anAuditRetentionShorterThanTheTableKeepsStopsTheStart() {
    context
        .withSystemProperties("SA_RETENTION_ADMIN_AUDIT=P365D")
        .run(c ->
            assertThat(c)
                .hasFailed()
                .getFailure()
                .rootCause()
                .hasMessageContaining("admin-audit must be at least P730D"));
  }

  @Test
  void thePublicAppHasNoAdmins() {
    context
        .withSystemProperties("SA_ADMINS=github:1")
        .run(c -> assertThat(c.getBean(IdentityProperties.class).admins()).isEmpty());
  }
}
