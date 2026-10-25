package ai.helper2.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AccessToken;

/**
 * GitHub sign-in through the real user service against a stub user-info endpoint: ADMIN follows the
 * immutable numeric id only. A login can be renamed and re-registered by someone else, so
 * "github:&lt;login&gt;" in the allow-list must never grant ADMIN.
 */
class AdminAllowListTest {

    static HttpServer userInfo;

    @BeforeAll
    static void startUserInfo() throws Exception {
        userInfo = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        userInfo.createContext("/user", exchange -> {
            byte[] body = "{\"id\":12345,\"login\":\"octocat\",\"name\":\"The Octocat\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        userInfo.start();
    }

    @AfterAll
    static void stopUserInfo() {
        userInfo.stop(0);
    }

    private static Set<String> rolesFor(Set<String> admins) {
        // The provider defaults Spring Boot uses for "github": user-name attribute "id".
        ClientRegistration github = CommonOAuth2Provider.GITHUB.getBuilder("github")
            .clientId("client").clientSecret("secret")
            .userInfoUri("http://127.0.0.1:" + userInfo.getAddress().getPort() + "/user")
            .build();
        OAuth2AccessToken token = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "token",
            Instant.now(), Instant.now().plusSeconds(60));
        return SecurityConfiguration.oauthUsers(admins).loadUser(new OAuth2UserRequest(github, token))
            .getAuthorities().stream().map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
    }

    @Test
    void numericGithubIdInTheAllowListGrantsAdmin() {
        assertThat(rolesFor(Set.of("github:12345"))).contains("ROLE_USER", "ROLE_ADMIN");
    }

    @Test
    void githubLoginInTheAllowListDoesNotGrantAdmin() {
        assertThat(rolesFor(Set.of("github:octocat"))).contains("ROLE_USER").doesNotContain("ROLE_ADMIN");
    }

    @Test
    void sameIdUnderAnotherProviderDoesNotGrantAdmin() {
        assertThat(rolesFor(Set.of("google:12345"))).doesNotContain("ROLE_ADMIN");
    }
}
