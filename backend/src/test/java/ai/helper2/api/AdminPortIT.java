package ai.helper2.api;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.TestcontainersConfiguration;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * ADR 0035: the back office answers only on the admin port; the public port knows nothing of it (404), even when the
 * path is percent-encoded or walks up with "..". Sign-in works on both ports, each with its own callback, so end
 * users sign in on the public host. Real HTTP: the second Tomcat connector is started for real.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "helper2.sources.github.enabled=false", "helper2.sources.huggingface.enabled=false",
    "helper2.sources.npm.enabled=false", "helper2.sources.mcp-registry.enabled=false", "helper2.sources.arxiv.enabled=false", "helper2.sources.tavily.enabled=false",
    "logging.structured.format.console=",
    "spring.security.oauth2.client.registration.test.client-id=test-client",
    "spring.security.oauth2.client.registration.test.client-secret=test-secret",
    "spring.security.oauth2.client.registration.test.authorization-grant-type=authorization_code",
    "spring.security.oauth2.client.registration.test.redirect-uri={baseUrl}/login/oauth2/code/{registrationId}",
    "spring.security.oauth2.client.provider.test.authorization-uri=https://provider.example/authorize",
    "spring.security.oauth2.client.provider.test.token-uri=https://provider.example/token",
    "spring.security.oauth2.client.provider.test.user-info-uri=https://provider.example/user",
    "spring.security.oauth2.client.provider.test.user-name-attribute=id",
})
@Import(TestcontainersConfiguration.class)
class AdminPortIT {

    static final int ADMIN_PORT = freePort();

    @DynamicPropertySource
    static void adminPort(DynamicPropertyRegistry r) {
        r.add("helper2.admin.port", () -> ADMIN_PORT);
    }

    @LocalServerPort
    int publicPort;

    final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    HttpResponse<String> get(int port, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void thePublicPortHasNoBackOffice() throws Exception {
        assertThat(get(publicPort, "/api/v1/admin/audit").statusCode()).isEqualTo(404);
        assertThat(get(publicPort, "/api/v1/admin").statusCode()).isEqualTo(404);
        assertThat(get(publicPort, "/admin").statusCode()).isEqualTo(404);
        assertThat(get(publicPort, "/admin/review").statusCode()).isEqualTo(404);
    }

    @Test
    void anEncodedOrWalkedPathDoesNotReachTheBackOfficeOnThePublicPort() throws Exception {
        assertThat(get(publicPort, "/api/v1/%61dmin/audit").statusCode()).isIn(400, 404);
        assertThat(get(publicPort, "/api/v1/x/../admin/audit").statusCode()).isIn(400, 404);
        assertThat(get(publicPort, "/api/v1//admin/audit").statusCode()).isIn(400, 404);
    }

    @Test
    void theAdminPortAnswersTheBackOfficeBehindSignIn() throws Exception {
        assertThat(get(ADMIN_PORT, "/api/v1/admin/audit").statusCode()).isEqualTo(401);
    }

    @Test
    void endUsersSignInOnThePublicPortAndAdminsOnTheAdminPortEachWithItsOwnCallback() throws Exception {
        HttpResponse<String> publicSignIn = get(publicPort, "/oauth2/authorization/test");
        assertThat(publicSignIn.statusCode()).isEqualTo(302);
        assertThat(publicSignIn.headers().firstValue("Location")).hasValueSatisfying(l -> assertThat(l)
            .startsWith("https://provider.example/authorize").contains("redirect_uri=http://localhost:" + publicPort + "/login/oauth2/code/test"));

        HttpResponse<String> adminSignIn = get(ADMIN_PORT, "/oauth2/authorization/test");
        assertThat(adminSignIn.statusCode()).isEqualTo(302);
        assertThat(adminSignIn.headers().firstValue("Location")).hasValueSatisfying(l -> assertThat(l)
            .contains("redirect_uri=http://localhost:" + ADMIN_PORT + "/login/oauth2/code/test"));
    }

    @Test
    void thePublicApiStaysOnThePublicPort() throws Exception {
        assertThat(get(publicPort, "/api/v1/me").statusCode()).isEqualTo(200);
    }

    static int freePort() {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
