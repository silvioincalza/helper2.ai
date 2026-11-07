package ai.helper2.identity.config;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.testing.ConfigBinding;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** Identity's settings bind from application.yaml, with and without the dev profile. */
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
        .withSystemProperties("H2_NOINDEX=true", "H2_RETENTION_ADMIN_AUDIT=P365D")
        .run(c -> {
          IdentityProperties identity = c.getBean(IdentityProperties.class);
          assertThat(identity.noindex()).isTrue();
          assertThat(c.getBean(IdentityRetentionProperties.class).adminAudit())
              .isEqualTo(Duration.ofDays(365));
        });
  }

  /** ADMIN is the back office's (ADR 0069): the public app reads no allow-list, whatever is set. */
  @Test
  void thePublicAppHasNoAdmins() {
    context
        .withSystemProperties("H2_ADMINS=github:1")
        .run(c -> assertThat(c.getBean(IdentityProperties.class).admins()).isEmpty());
  }
}
