package ai.helper2.identity.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * ADR 0035: with a sign-in provider configured, the app refuses to start without an admin port,
 * except on a developer machine.
 */
class AdminPortFailClosedTest {

  static MockEnvironment withGitHub(String... profiles) {
    MockEnvironment environment =
        new MockEnvironment()
            .withProperty("spring.security.oauth2.client.registration.github.client-id", "id")
            .withProperty(
                "spring.security.oauth2.client.registration.github.client-secret", "secret");
    environment.setActiveProfiles(profiles);
    return environment;
  }

  @Test
  void refusesToStartWithASignInProviderAndNoAdminPort() {
    assertThatThrownBy(() ->
            AdminPortConfiguration.requireAdminPortWithRealSignIn(
                new AdminPort(0), withGitHub("llm-nebius", "oauth-github")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("H2_ADMIN_PORT");
  }

  /**
   * Set through environment variables, without the oauth-github profile, it is still a provider.
   */
  @Test
  void anyRegistrationCountsNotOnlyTheProfile() {
    assertThatThrownBy(() ->
            AdminPortConfiguration.requireAdminPortWithRealSignIn(
                new AdminPort(0), withGitHub("llm-nebius")))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void aRegistrationThatCannotBeReadStillCounts() {
    MockEnvironment environment =
        new MockEnvironment()
            .withProperty(
                "spring.security.oauth2.client.registration.github.client-id",
                "${H2_MISSING_CLIENT_ID}");
    assertThatThrownBy(() ->
            AdminPortConfiguration.requireAdminPortWithRealSignIn(new AdminPort(0), environment))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void startsWithASignInProviderAndAnAdminPort() {
    assertThatCode(() ->
            AdminPortConfiguration.requireAdminPortWithRealSignIn(
                new AdminPort(8082), withGitHub("oauth-github")))
        .doesNotThrowAnyException();
  }

  @Test
  void aDeveloperMachineKeepsOnePort() {
    assertThatCode(() ->
            AdminPortConfiguration.requireAdminPortWithRealSignIn(
                new AdminPort(0), withGitHub("dev", "oauth-github")))
        .doesNotThrowAnyException();
  }

  @Test
  void withoutASignInProviderThereIsNoAdminToProtect() {
    assertThatCode(() ->
            AdminPortConfiguration.requireAdminPortWithRealSignIn(
                new AdminPort(0), new MockEnvironment()))
        .doesNotThrowAnyException();
  }
}
