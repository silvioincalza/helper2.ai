package ai.helper2.identity.config;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.testing.ConfigBinding;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * The back office's GitHub sign-in callback (ADR 0032): pinned to the admin host when
 * H2_ADMIN_BASE_URL is set, this request's own address ({baseUrl}) while it is not.
 */
class SignInCallbackBindingTest {

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(OAuth2ClientProperties.class)
  static class Binding {}

  final ApplicationContextRunner context =
      ConfigBinding.of(Binding.class)
          .withPropertyValues("spring.profiles.active=oauth-github")
          .withSystemProperties(
              "H2_GITHUB_OAUTH_CLIENT_ID=test-id", "H2_GITHUB_OAUTH_CLIENT_SECRET=test-secret");

  static String callback(org.springframework.context.ApplicationContext c) {
    return c.getBean(OAuth2ClientProperties.class).getRegistration().get("github").getRedirectUri();
  }

  @Test
  void theCallbackIsPinnedToTheAdminHost() {
    context
        .withSystemProperties("H2_ADMIN_BASE_URL=https://admin.example.org")
        .run(c ->
            assertThat(callback(c))
                .isEqualTo("https://admin.example.org/login/oauth2/code/{registrationId}"));
  }

  @Test
  void withoutTheAdminHostItIsThisRequestsOwnAddress() {
    context.run(
        c -> assertThat(callback(c)).isEqualTo("{baseUrl}/login/oauth2/code/{registrationId}"));
  }
}
