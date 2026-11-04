package ai.helper2.identity;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** ADR 0035: with GitHub sign-in on, the app refuses to start without an admin port, except on a developer machine. */
class AdminPortFailClosedTest {

    static MockEnvironment profiles(String... active) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(active);
        return environment;
    }

    @Test
    void refusesToStartWithGitHubSignInAndNoAdminPort() {
        assertThatThrownBy(() -> AdminPortConfiguration.requireAdminPortWithRealSignIn(new AdminPort(0),
            profiles("llm-nebius", "oauth-github")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("H2_ADMIN_PORT");
    }

    @Test
    void startsWithGitHubSignInAndAnAdminPort() {
        assertThatCode(() -> AdminPortConfiguration.requireAdminPortWithRealSignIn(new AdminPort(8082),
            profiles("llm-nebius", "oauth-github"))).doesNotThrowAnyException();
    }

    @Test
    void aDeveloperMachineKeepsOnePort() {
        assertThatCode(() -> AdminPortConfiguration.requireAdminPortWithRealSignIn(new AdminPort(0),
            profiles("dev", "llm-nebius", "oauth-github"))).doesNotThrowAnyException();
    }

    @Test
    void withoutARealSignInThereIsNoAdminToProtect() {
        assertThatCode(() -> AdminPortConfiguration.requireAdminPortWithRealSignIn(new AdminPort(0), profiles("llm-nebius")))
            .doesNotThrowAnyException();
    }
}
