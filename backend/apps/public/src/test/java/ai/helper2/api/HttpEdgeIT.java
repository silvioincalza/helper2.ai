package ai.helper2.api;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.TestcontainersConfiguration;
import ai.helper2.testing.SourcesOff;
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

@SourcesOff
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "helper2.rate-limit.search-per-minute=3",
      "helper2.rate-limit.recent-per-minute=3",
      "helper2.rate-limit.guide-per-minute=3",
      "helper2.rate-limit.default-per-minute=5",
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

  @LocalServerPort int port;

  @Autowired StringRedisTemplate redis;

  @BeforeEach
  void resetRateLimits() {
    TestcontainersConfiguration.adminRedis()
        .delete(TestcontainersConfiguration.adminRedis().keys("h2:v1:rate:*"));
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
    String cookie =
        me.headers().allValues("Set-Cookie").stream()
            .filter(c -> c.startsWith("XSRF-TOKEN="))
            .findFirst()
            .orElseThrow();
    assertThat(cookie).doesNotContainIgnoringCase("HttpOnly");
    assertThat(me.headers().firstValue("Content-Security-Policy"))
        .hasValueSatisfying(
            v -> assertThat(v).contains("frame-ancestors 'none'").contains("script-src 'self'"));
    assertThat(me.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
    assertThat(me.headers().firstValue("X-Robots-Tag")).as("indexable unless H2_NOINDEX").isEmpty();

    String token = cookie.substring("XSRF-TOKEN=".length(), cookie.indexOf(';'));
    String body = "{\"kind\":\"WRONG\",\"message\":\"definitely wrong\"}";
    String url = "/api/v1/resources/00000000-0000-0000-0000-000000000000/reports";
    HttpResponse<String> noHeader =
        send(
            HttpRequest.newBuilder(uri(url))
                .header("Content-Type", "application/json")
                .header("Cookie", "XSRF-TOKEN=" + token)
                .POST(HttpRequest.BodyPublishers.ofString(body)));
    assertThat(noHeader.statusCode()).isEqualTo(403);
    HttpResponse<String> withHeader =
        send(
            HttpRequest.newBuilder(uri(url))
                .header("Content-Type", "application/json")
                .header("Cookie", "XSRF-TOKEN=" + token)
                .header("X-XSRF-TOKEN", token)
                .POST(HttpRequest.BodyPublishers.ofString(body)));
    assertThat(withHeader.statusCode()).as(withHeader.body()).isEqualTo(404);
  }

  @Test
  void onlyMeWritesTheCsrfCookieSoCacheableResponsesCarryNoSetCookie() throws Exception {
    for (String path :
        java.util.List.of("/api/v1/recent", "/api/v1/sources", "/", "/assets/missing.js")) {
      HttpResponse<String> r = send(HttpRequest.newBuilder(uri(path)));
      assertThat(r.headers().allValues("Set-Cookie"))
          .as(path)
          .noneMatch(c -> c.startsWith("XSRF-TOKEN="));
    }
    HttpResponse<String> me = send(HttpRequest.newBuilder(uri("/api/v1/me")));
    assertThat(me.headers().allValues("Set-Cookie")).anyMatch(c -> c.startsWith("XSRF-TOKEN="));
  }

  @Test
  void aPostWithoutTheCookieIsRefusedAndGetsOneForTheRetry() throws Exception {
    HttpResponse<String> r =
        send(
            HttpRequest.newBuilder(
                    uri("/api/v1/resources/00000000-0000-0000-0000-000000000000/reports"))
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        "{\"kind\":\"WRONG\",\"message\":\"definitely wrong\"}")));
    assertThat(r.statusCode()).isEqualTo(403);
    assertThat(r.headers().allValues("Set-Cookie")).anyMatch(c -> c.startsWith("XSRF-TOKEN="));
  }

  @Test
  void everyResponseSaysTheSiteUsesNoCameraMicrophoneLocationPaymentOrUsb() throws Exception {
    for (String path : java.util.List.of("/api/v1/me", "/api/v1/sources")) {
      assertThat(send(HttpRequest.newBuilder(uri(path))).headers().firstValue("Permissions-Policy"))
          .as(path)
          .contains("camera=(), microphone=(), geolocation=(), payment=(), usb=()");
    }
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
    assertThat(r.headers().firstValue("Content-Type"))
        .hasValueSatisfying(v -> assertThat(v).contains("problem+json"));
  }

  @Test
  void operationsWithNoLimitOfTheirOwnShareTheDefaultLimit() throws Exception {
    java.util.List<Integer> statuses = new java.util.ArrayList<>();
    for (String path :
        java.util.List.of(
            "/api/v1/sources",
            "/api/v1/events/upcoming",
            "/api/v1/sources",
            "/api/v1/resources/" + java.util.UUID.randomUUID(),
            "/api/v1/sources",
            "/api/v1/events/upcoming",
            "/api/v1/sources")) {
      statuses.add(send(HttpRequest.newBuilder(uri(path))).statusCode());
    }

    assertThat(statuses.subList(0, 5)).doesNotContain(429);
    assertThat(statuses.subList(5, 7)).containsOnly(429);
    assertThat(send(HttpRequest.newBuilder(uri("/api/v1/recent"))).statusCode())
        .as("an operation with its own limit does not pay for the others")
        .isEqualTo(200);
  }

  @Test
  void theSitemapCountsTowardTheDefaultLimit_theOtherCrawlerFilesDoNot() throws Exception {
    java.util.List<Integer> sitemap = new java.util.ArrayList<>();
    for (int i = 0; i < 7; i++) {
      sitemap.add(send(HttpRequest.newBuilder(uri("/sitemap.xml"))).statusCode());
    }

    assertThat(sitemap.subList(0, 5)).doesNotContain(429);
    assertThat(sitemap.subList(5, 7)).containsOnly(429);
    assertThat(send(HttpRequest.newBuilder(uri("/robots.txt"))).statusCode()).isEqualTo(200);
  }

  @Test
  void aSpoofedXForwardedForDoesNotResetThePerClientLimit() throws Exception {
    int ok = 0;
    int last = 0;
    for (int i = 0; i < 6; i++) {
      HttpResponse<String> r =
          send(
              HttpRequest.newBuilder(uri("/api/v1/recent"))
                  .header("X-Forwarded-For", "203.0.113." + (10 + i)));
      last = r.statusCode();
      ok += last == 200 ? 1 : 0;
    }
    assertThat(ok).isEqualTo(3);
    assertThat(last).isEqualTo(429);
  }

  private static java.util.List<String> sessionCookies(HttpResponse<?> r) {
    return r.headers().allValues("Set-Cookie").stream()
        .filter(c -> c.startsWith("SESSION="))
        .toList();
  }

  @Test
  void anonymousRequestsNeverCreateASession() throws Exception {
    java.util.Set<String> before =
        TestcontainersConfiguration.adminRedis().keys("h2:v1:session:public:*");
    java.util.List<HttpResponse<String>> responses =
        java.util.List.of(
            send(HttpRequest.newBuilder(uri("/api/v1/search?q=session"))),
            send(HttpRequest.newBuilder(uri("/api/v1/me"))),
            send(HttpRequest.newBuilder(uri("/api/v1/recent"))),
            send(HttpRequest.newBuilder(uri("/api/v1/me/bookmarks"))),
            send(HttpRequest.newBuilder(uri("/api/v1/admin/review-items"))));
    assertThat(responses)
        .extracting(HttpResponse::statusCode)
        .containsExactly(200, 200, 200, 401, 404);
    assertThat(responses).allSatisfy(r -> assertThat(sessionCookies(r)).isEmpty());
    assertThat(TestcontainersConfiguration.adminRedis().keys("h2:v1:session:public:*"))
        .isEqualTo(before);
  }

  @Test
  void signInKeepsItsStateInARedisSessionThatAnyReplicaCanRead() throws Exception {
    HttpResponse<String> r = send(HttpRequest.newBuilder(uri("/oauth2/authorization/test")));
    assertThat(r.statusCode()).isEqualTo(302);
    assertThat(r.headers().firstValue("Location"))
        .hasValueSatisfying(l -> assertThat(l).startsWith("https://provider.example/authorize?"));

    String cookie = sessionCookies(r).getFirst();
    assertThat(cookie).contains("HttpOnly").contains("Secure").contains("SameSite=Lax");
    String id =
        new String(
            java.util.Base64.getDecoder()
                .decode(cookie.substring("SESSION=".length(), cookie.indexOf(';'))));
    String key = "h2:v1:session:public:sessions:" + id;
    assertThat(redis.hasKey(key)).isTrue();
    assertThat(redis.getExpire(key)).isBetween(25 * 60L, 35 * 60L);
  }

  @Test
  void anEncodedGuidePathCountsTowardTheGuideLimit() throws Exception {
    HttpResponse<String> me = send(HttpRequest.newBuilder(uri("/api/v1/me")));
    String cookie =
        me.headers().allValues("Set-Cookie").stream()
            .filter(c -> c.startsWith("XSRF-TOKEN="))
            .findFirst()
            .orElseThrow();
    String token = cookie.substring("XSRF-TOKEN=".length(), cookie.indexOf(';'));
    String body = "{\"request\":\"translate a document\"}";
    java.util.List<Integer> statuses = new java.util.ArrayList<>();
    for (String path :
        java.util.List.of(
            "/api/v1/%67uide/stream",
            "/api/v1/guide/stream",
            "/api/v1/%67uide/stream",
            "/api/v1/%67uide/stream",
            "/api/v1/guide/stream",
            "/api/v1/%67uide/stream")) {
      HttpResponse<String> r =
          send(
              HttpRequest.newBuilder(uri(path))
                  .header("Content-Type", "application/json")
                  .header("Cookie", "XSRF-TOKEN=" + token)
                  .header("X-XSRF-TOKEN", token)
                  .POST(HttpRequest.BodyPublishers.ofString(body)));
      statuses.add(r.statusCode());
    }
    assertThat(statuses.subList(0, 3))
        .as("the encoded path really reaches the guide")
        .containsOnly(200);
    assertThat(statuses.subList(3, 6))
        .as("encoded and plain paths share one budget")
        .containsOnly(429);
  }

  final HttpClient noRedirects =
      HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

  HttpResponse<String> getAt(int port, String path) throws Exception {
    return noRedirects.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
        HttpResponse.BodyHandlers.ofString());
  }

  String csrfTokenAt(int at) throws Exception {
    String cookie =
        getAt(at, "/api/v1/me").headers().allValues("Set-Cookie").stream()
            .filter(c -> c.startsWith("XSRF-TOKEN="))
            .findFirst()
            .orElseThrow();
    return cookie.substring("XSRF-TOKEN=".length(), cookie.indexOf(';'));
  }

  @Test
  void noGeneratedSignInOrSignOutPageWritesTheCsrfTokenIntoHtml() throws Exception {
    for (String path : java.util.List.of("/login", "/logout")) {
      HttpResponse<String> r = getAt(port, path);
      assertThat(r.body()).as(path).doesNotContain("_csrf").doesNotContain("<form");
    }
  }

  @Test
  void thePublicSiteNeedsNoOriginForItsWrites() throws Exception {
    String token = csrfTokenAt(port);
    HttpResponse<String> report =
        send(
            HttpRequest.newBuilder(
                    uri("/api/v1/resources/00000000-0000-0000-0000-000000000000/reports"))
                .header("Content-Type", "application/json")
                .header("Cookie", "XSRF-TOKEN=" + token)
                .header("X-XSRF-TOKEN", token)
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        "{\"kind\":\"WRONG\",\"message\":\"definitely wrong\"}")));
    assertThat(report.statusCode()).as(report.body()).isEqualTo(404);
  }

  @Test
  void thePublicSiteHasNoBackOffice() throws Exception {
    HttpResponse<String> audit = getAt(port, "/api/v1/admin/audit");
    assertThat(audit.statusCode()).isEqualTo(404);
    assertThat(audit.body())
        .isEqualTo(
            "{\"type\":\"https://helper2.ai/problems/not-found\",\"title\":\"Not found\","
                + "\"status\":404,\"code\":\"not-found\"}");
    assertThat(audit.headers().firstValue("Content-Type"))
        .hasValueSatisfying(v -> assertThat(v).startsWith("application/problem+json"));
    assertThat(audit.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
    assertThat(audit.headers().firstValue("Cache-Control")).contains("no-store");
    assertThat(audit.headers().firstValue("Content-Security-Policy"))
        .hasValueSatisfying(v -> assertThat(v).contains("frame-ancestors 'none'"));
    assertThat(getAt(port, "/api/v1/admin").statusCode()).isEqualTo(404);
    assertThat(getAt(port, "/admin").statusCode()).isEqualTo(404);
    assertThat(getAt(port, "/admin/review").statusCode()).isEqualTo(404);
    assertThat(getAt(port, "/api/v1/admin/overview").statusCode()).isEqualTo(404);
  }

  @Test
  void anEncodedOrWalkedPathDoesNotReachABackOfficePath() throws Exception {
    assertThat(getAt(port, "/api/v1/%61dmin/audit").statusCode()).isIn(400, 404);
    assertThat(getAt(port, "/api/v1/x/../admin/audit").statusCode()).isIn(400, 404);
    assertThat(getAt(port, "/api/v1//admin/audit").statusCode()).isIn(400, 404);
  }

  @Test
  void endUsersSignInHereWithThisHostsOwnCallback() throws Exception {
    HttpResponse<String> publicSignIn = getAt(port, "/oauth2/authorization/test");
    assertThat(publicSignIn.statusCode()).isEqualTo(302);
    assertThat(publicSignIn.headers().firstValue("Location"))
        .hasValueSatisfying(l ->
            assertThat(l)
                .startsWith("https://provider.example/authorize")
                .contains("redirect_uri=http://localhost:" + port + "/login/oauth2/code/test"));
  }

  static int freePort() {
    try (ServerSocket s = new ServerSocket(0)) {
      return s.getLocalPort();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }
}
