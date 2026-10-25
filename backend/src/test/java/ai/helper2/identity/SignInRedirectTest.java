package ai.helper2.identity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Sign-in lives on the admin page only, so OAuth2 must send the user back there. */
class SignInRedirectTest {

    @Test
    void oauthReturnsToTheAdminPageWithOrWithoutATrailingSlash() {
        assertThat(SecurityConfiguration.adminPage("/")).isEqualTo("/admin");
        assertThat(SecurityConfiguration.adminPage("http://localhost:5173/")).isEqualTo("http://localhost:5173/admin");
        assertThat(SecurityConfiguration.adminPage("https://helper2.ai")).isEqualTo("https://helper2.ai/admin");
    }
}
