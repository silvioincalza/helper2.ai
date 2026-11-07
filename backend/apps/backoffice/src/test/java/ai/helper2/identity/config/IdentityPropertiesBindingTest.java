package ai.helper2.identity.config;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.testing.ConfigBinding;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** Identity's settings bind from application.yaml, with and without the dev profile. */
/** The back office's sign-in settings bind from its own application.yaml (ADR 0069). */
class IdentityPropertiesBindingTest {

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties({IdentityProperties.class, IdentityRetentionProperties.class})
  static class Binding {}

  final ApplicationContextRunner context = ConfigBinding.of(Binding.class);

  @Test
  void aSignInReturnsToTheAdminWebApp() {
    context.run(c ->
        assertThat(c.getBean(IdentityProperties.class))
            .isEqualTo(new IdentityProperties("/admin", List.of(), false, false)));
  }

  @Test
  void theDevProfileReturnsToTheAdminDevServer() {
    context
        .withPropertyValues("spring.profiles.active=dev")
        .run(c -> {
          IdentityProperties identity = c.getBean(IdentityProperties.class);
          assertThat(identity.frontendUrl()).isEqualTo("http://localhost:5174/admin");
          assertThat(identity.metricsPublic()).isTrue();
        });
  }

  @Test
  void theAllowListComesFromH2Admins() {
    context
        .withSystemProperties("H2_ADMINS=github:1, ,oidc:owner@example.org ")
        .run(c ->
            assertThat(c.getBean(IdentityProperties.class).admins())
                .containsExactly("github:1", "oidc:owner@example.org"));
  }
}
