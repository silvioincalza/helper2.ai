package ai.helper2.api;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.TestcontainersConfiguration;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Real HTTP through the servlet container: cookies, sessions, CSRF, security headers, rate limiting. MockMvc would
 * skip Tomcat's URI decoding and the forward-headers handling, which is what the bypass tests exercise. A sign-in
 * provider "test" is registered so the sign-in redirect can be followed up to the provider.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "helper2.sources.github.enabled=false", "helper2.sources.huggingface.enabled=false",
    "helper2.sources.npm.enabled=false", "helper2.sources.mcp-registry.enabled=false", "helper2.sources.arxiv.enabled=false", "helper2.sources.tavily.enabled=false",
    "helper2.rate-limit.search-per-minute=3", "helper2.rate-limit.recent-per-minute=3", "helper2.rate-limit.guide-per-minute=3",
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
class HttpEdgeIT {

    /**
     * The admin port (ADR 0035) lives in this context too, not in a context of its own: one more distinct context
     * would make the context cache (32) evict another, and closing it stops the shared Testcontainers.
     */
    static final int ADMIN_PORT = freePort();

    @DynamicPropertySource
    static void adminPort(DynamicPropertyRegistry r) {
        r.add("helper2.admin.port", () -> ADMIN_PORT);
    }

    @LocalServerPort
    int port;

    @Autowired
    StringRedisTemplate redis;

    /** Rate-limit counts are in the Redis that every test context shares: each test starts from zero. */
    @BeforeEach
    void resetRateLimits() {
        redis.delete(redis.keys("h2:v1:rate:*"));
    }

    final HttpClient http = HttpClient.newHttpClient();

    HttpResponse<String> send(HttpRequest.Builder b) throws Exception {
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    @Test
    void meWritesAReadableCsrfCookieAndPostWithoutTheHeaderIsRejected() throws Exception {
        HttpResponse<String> me = send(HttpRequest.newBuilder(uri("/api/v1/me")));
        String cookie = me.headers().allValues("Set-Cookie").stream().filter(c -> c.startsWith("XSRF-TOKEN=")).findFirst().orElseThrow();
        assertThat(cookie).doesNotContainIgnoringCase("HttpOnly");
        assertThat(me.headers().firstValue("Content-Security-Policy")).hasValueSatisfying(v -> assertThat(v).contains("frame-ancestors 'none'").contains("script-src 'self'"));
        assertThat(me.headers().firstValue("X-Content-Type-Options")).contains("nosniff");

        String token = cookie.substring("XSRF-TOKEN=".length(), cookie.indexOf(';'));
        String body = "{\"kind\":\"WRONG\",\"message\":\"definitely wrong\"}";
        String url = "/api/v1/resources/00000000-0000-0000-0000-000000000000/reports";
        HttpResponse<String> noHeader = send(HttpRequest.newBuilder(uri(url)).header("Content-Type", "application/json")
            .header("Cookie", "XSRF-TOKEN=" + token).POST(HttpRequest.BodyPublishers.ofString(body)));
        assertThat(noHeader.statusCode()).isEqualTo(403);
        HttpResponse<String> withHeader = send(HttpRequest.newBuilder(uri(url)).header("Content-Type", "application/json")
            .header("Cookie", "XSRF-TOKEN=" + token).header("X-XSRF-TOKEN", token).POST(HttpRequest.BodyPublishers.ofString(body)));
        assertThat(withHeader.statusCode()).as(withHeader.body()).isEqualTo(404);
    }

    @Test
    void searchIsRateLimitedWithRetryAfter() throws Exception {
        int last = 0;
        HttpResponse<String> r = null;
        for (int i = 0; i < 5; i++) {
            r = send(HttpRequest.newBuilder(uri("/api/v1/search?q=rate" + i)));
            last = r.statusCode();
        }
        assertThat(last).isEqualTo(429);
        assertThat(r.headers().firstValue("Retry-After")).isPresent();
        assertThat(r.headers().firstValue("Content-Type")).hasValueSatisfying(v -> assertThat(v).contains("problem+json"));
    }

    @Test
    void aSpoofedXForwardedForDoesNotResetThePerClientLimit() throws Exception {
        // Default forward-headers strategy (none): the client address is the socket peer, whatever XFF says.
        int ok = 0;
        int last = 0;
        for (int i = 0; i < 6; i++) {
            HttpResponse<String> r = send(HttpRequest.newBuilder(uri("/api/v1/recent"))
                .header("X-Forwarded-For", "203.0.113." + (10 + i)));
            last = r.statusCode();
            ok += last == 200 ? 1 : 0;
        }
        assertThat(ok).isEqualTo(3);
        assertThat(last).isEqualTo(429);
    }

    private static java.util.List<String> sessionCookies(HttpResponse<?> r) {
        return r.headers().allValues("Set-Cookie").stream().filter(c -> c.startsWith("SESSION=")).toList();
    }

    @Test
    void anonymousRequestsNeverCreateASession() throws Exception {
        java.util.Set<String> before = redis.keys("helper2:session:*");
        java.util.List<HttpResponse<String>> responses = java.util.List.of(
            send(HttpRequest.newBuilder(uri("/api/v1/search?q=session"))),
            send(HttpRequest.newBuilder(uri("/api/v1/me"))),
            send(HttpRequest.newBuilder(uri("/api/v1/recent"))),
            send(HttpRequest.newBuilder(uri("/api/v1/me/bookmarks"))),
            send(HttpRequest.newBuilder(uri("/api/v1/admin/review-items"))));
        // The back office is not on the public port (ADR 0035): 404, still without a session.
        assertThat(responses).extracting(HttpResponse::statusCode).containsExactly(200, 200, 200, 401, 404);
        assertThat(responses).allSatisfy(r -> assertThat(sessionCookies(r)).isEmpty());
        assertThat(redis.keys("helper2:session:*")).isEqualTo(before);
    }

    @Test
    void signInKeepsItsStateInARedisSessionThatAnyReplicaCanRead() throws Exception {
        HttpResponse<String> r = send(HttpRequest.newBuilder(uri("/oauth2/authorization/test")));
        assertThat(r.statusCode()).isEqualTo(302);
        assertThat(r.headers().firstValue("Location")).hasValueSatisfying(l -> assertThat(l).startsWith("https://provider.example/authorize?"));

        String cookie = sessionCookies(r).getFirst();
        assertThat(cookie).contains("HttpOnly").contains("Secure").contains("SameSite=Lax");
        String id = new String(java.util.Base64.getDecoder().decode(cookie.substring("SESSION=".length(), cookie.indexOf(';'))));
        String key = "helper2:session:sessions:" + id;
        assertThat(redis.hasKey(key)).isTrue();
        // server.servlet.session.timeout: 30 minutes by default.
        assertThat(redis.getExpire(key)).isBetween(25 * 60L, 35 * 60L);
    }

    @Test
    void anEncodedGuidePathCountsTowardTheGuideLimit() throws Exception {
        HttpResponse<String> me = send(HttpRequest.newBuilder(uri("/api/v1/me")));
        String cookie = me.headers().allValues("Set-Cookie").stream().filter(c -> c.startsWith("XSRF-TOKEN=")).findFirst().orElseThrow();
        String token = cookie.substring("XSRF-TOKEN=".length(), cookie.indexOf(';'));
        String body = "{\"request\":\"translate a document\"}";
        // "%67" is "g": Tomcat decodes it and Spring routes it to the same guide handler.
        java.util.List<Integer> statuses = new java.util.ArrayList<>();
        for (String path : java.util.List.of("/api/v1/%67uide", "/api/v1/guide", "/api/v1/%67uide", "/api/v1/%67uide", "/api/v1/guide", "/api/v1/%67uide")) {
            HttpResponse<String> r = send(HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json")
                .header("Cookie", "XSRF-TOKEN=" + token).header("X-XSRF-TOKEN", token).POST(HttpRequest.BodyPublishers.ofString(body)));
            statuses.add(r.statusCode());
        }
        assertThat(statuses.subList(0, 3)).as("the encoded path really reaches the guide").containsOnly(200);
        assertThat(statuses.subList(3, 6)).as("encoded and plain paths share one budget").containsOnly(429);
    }

    // ── Admin port (ADR 0035) ──

    final HttpClient noRedirects = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    HttpResponse<String> getAt(int port, String path) throws Exception {
        return noRedirects.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void thePublicPortHasNoBackOffice() throws Exception {
        assertThat(getAt(port, "/api/v1/admin/audit").statusCode()).isEqualTo(404);
        assertThat(getAt(port, "/api/v1/admin").statusCode()).isEqualTo(404);
        assertThat(getAt(port, "/admin").statusCode()).isEqualTo(404);
        assertThat(getAt(port, "/admin/review").statusCode()).isEqualTo(404);
    }

    @Test
    void anEncodedOrWalkedPathDoesNotReachTheBackOfficeOnThePublicPort() throws Exception {
        assertThat(getAt(port, "/api/v1/%61dmin/audit").statusCode()).isIn(400, 404);
        assertThat(getAt(port, "/api/v1/x/../admin/audit").statusCode()).isIn(400, 404);
        assertThat(getAt(port, "/api/v1//admin/audit").statusCode()).isIn(400, 404);
    }

    @Test
    void theAdminPortAnswersTheBackOfficeBehindSignIn() throws Exception {
        assertThat(getAt(ADMIN_PORT, "/api/v1/admin/audit").statusCode()).isEqualTo(401);
    }

    @Test
    void endUsersSignInOnThePublicPortAndAdminsOnTheAdminPortEachWithItsOwnCallback() throws Exception {
        HttpResponse<String> publicSignIn = getAt(port, "/oauth2/authorization/test");
        assertThat(publicSignIn.statusCode()).isEqualTo(302);
        assertThat(publicSignIn.headers().firstValue("Location")).hasValueSatisfying(l -> assertThat(l)
            .startsWith("https://provider.example/authorize").contains("redirect_uri=http://localhost:" + port + "/login/oauth2/code/test"));

        HttpResponse<String> adminSignIn = getAt(ADMIN_PORT, "/oauth2/authorization/test");
        assertThat(adminSignIn.statusCode()).isEqualTo(302);
        assertThat(adminSignIn.headers().firstValue("Location")).hasValueSatisfying(l -> assertThat(l)
            .contains("redirect_uri=http://localhost:" + ADMIN_PORT + "/login/oauth2/code/test"));
    }

    @Test
    void thePublicApiStaysOnThePublicPort() throws Exception {
        assertThat(getAt(port, "/api/v1/me").statusCode()).isEqualTo(200);
    }

    static int freePort() {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
