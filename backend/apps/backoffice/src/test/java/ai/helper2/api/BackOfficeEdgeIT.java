package ai.helper2.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.helper2.TestcontainersConfiguration;
import ai.helper2.testing.SourcesOff;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import java.util.List;
import java.util.Map;
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
      "logging.structured.format.console=",
      "spring.security.oauth2.client.registration.test.client-id=test-client",
      "spring.security.oauth2.client.registration.test.client-secret=test-secret",
      "spring.security.oauth2.client.registration.test.authorization-grant-type=authorization_code",
      "spring.security.oauth2.client.registration.test.redirect-uri=https://admin.example.org/login/oauth2/code/{registrationId}",
      "spring.security.oauth2.client.provider.test.authorization-uri=https://provider.example/authorize",
      "spring.security.oauth2.client.provider.test.token-uri=https://provider.example/token",
      "spring.security.oauth2.client.provider.test.user-info-uri=https://provider.example/user",
      "spring.security.oauth2.client.provider.test.user-name-attribute=id",
      "helper2.admin.public-base-url=https://public.example.org",
    })
@Import(TestcontainersConfiguration.class)
class BackOfficeEdgeIT {

  static final String CSP =
      "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; "
          + "connect-src 'self'; font-src 'self'; object-src 'none'; base-uri 'none'; "
          + "form-action 'self'; frame-ancestors 'none'; frame-src 'none'; worker-src 'none'";

  static final Map<String, String> HEADERS =
      Map.of(
          "Content-Security-Policy", CSP,
          "X-Frame-Options", "DENY",
          "X-Content-Type-Options", "nosniff",
          "Referrer-Policy", "no-referrer",
          "Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=(), usb=()",
          "Cross-Origin-Opener-Policy", "same-origin",
          "Cross-Origin-Resource-Policy", "same-origin",
          "X-Robots-Tag", "noindex, nofollow",
          "Strict-Transport-Security", "max-age=31536000");

  static final String NOT_FOUND =
      "{\"type\":\"https://helper2.ai/problems/not-found\",\"title\":\"Not found\","
          + "\"status\":404,\"code\":\"not-found\"}";

  @LocalServerPort int port;

  @Autowired StringRedisTemplate redis;

  final HttpClient http =
      HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

  HttpResponse<String> send(HttpRequest.Builder b) throws Exception {
    return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
  }

  HttpResponse<String> get(String path) throws Exception {
    return send(HttpRequest.newBuilder(uri(path)));
  }

  URI uri(String path) {
    return URI.create("http://localhost:" + port + path);
  }

  String origin() {
    return "http://localhost:" + port;
  }

  static void hasTheHeaders(HttpResponse<?> r, String cacheControl) {
    HEADERS.forEach(
        (name, value) -> assertThat(r.headers().allValues(name)).as(name).containsExactly(value));
    assertThat(r.headers().allValues("Cache-Control")).containsExactly(cacheControl);
  }

  @Test
  void theAppHasEveryHeaderAndIsNeverStored() throws Exception {
    HttpResponse<String> r = get("/admin");
    assertThat(r.statusCode()).isEqualTo(200);
    assertThat(r.body()).contains("data-test=\"admin-shell\"");
    hasTheHeaders(r, "no-store");
  }

  @Test
  void aDeepLinkOpensTheApp() throws Exception {
    for (String path : List.of("/admin/", "/admin/review?kind=USER_REPORT", "/admin/scout/runs")) {
      HttpResponse<String> r = get(path);
      assertThat(r.statusCode()).as(path).isEqualTo(200);
      assertThat(r.body()).as(path).contains("data-test=\"admin-shell\"");
      hasTheHeaders(r, "no-store");
    }
  }

  @Test
  void aHashedAssetIsPrivateAndKeptAYear() throws Exception {
    HttpResponse<String> r = get("/admin/assets/admin-test.js");
    assertThat(r.statusCode()).isEqualTo(200);
    assertThat(r.body()).contains("console.log");
    assertThat(r.headers().firstValue("Content-Type"))
        .hasValueSatisfying(t -> assertThat(t).contains("javascript"));
    hasTheHeaders(r, "private, max-age=31536000, immutable");
  }

  @Test
  void theAppsOtherFilesAreRevalidated() throws Exception {
    for (String path : List.of("/admin/theme.js", "/admin/favicon.svg")) {
      HttpResponse<String> r = get(path);
      assertThat(r.statusCode()).as(path).isEqualTo(200);
      hasTheHeaders(r, "no-cache");
    }
  }

  @Test
  void theActuatorTakesNoWriteAndARefusedAssetIsNeverCached() throws Exception {
    HttpResponse<String> actuatorWrite =
        send(
            HttpRequest.newBuilder(uri("/actuator/loggers/ai.helper2"))
                .header("Origin", origin())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"configuredLevel\":\"TRACE\"}")));
    assertThat(actuatorWrite.statusCode()).isEqualTo(404);
    hasTheHeaders(actuatorWrite, "no-store");
    HttpResponse<String> assetWrite =
        send(
            HttpRequest.newBuilder(uri("/admin/assets/admin.js"))
                .header("Origin", origin())
                .POST(HttpRequest.BodyPublishers.noBody()));
    assertThat(assetWrite.statusCode()).isEqualTo(404);
    hasTheHeaders(assetWrite, "no-store");
  }

  @Test
  void anythingElseIsAPlainNotFoundWithTheHeaders() throws Exception {
    for (String path :
        List.of(
            "/assets/app.js",
            "/search",
            "/sources",
            "/api/v1/recent",
            "/api/v1/policy",
            "/api/v1/site",
            "/api/v1/me/bookmarks",
            "/admin/missing.js",
            "/admin/assets/missing.js",
            "/login",
            "/logout",
            "/robots.txt",
            "/actuatorx")) {
      HttpResponse<String> r = get(path);
      assertThat(r.statusCode()).as(path).isEqualTo(404);
      assertThat(r.body()).as(path).doesNotContain(path.substring(1)).doesNotContain("<form");
      hasTheHeaders(r, "no-store");
    }
    assertThat(get("/api/v1/recent").body()).isEqualTo(NOT_FOUND);
  }

  @Test
  void anApiRefusalIsAProblemWithTheHeaders() throws Exception {
    HttpResponse<String> r = get("/api/v1/admin/audit");
    assertThat(r.statusCode()).isEqualTo(401);
    assertThat(r.headers().firstValue("Content-Type"))
        .hasValueSatisfying(t -> assertThat(t).startsWith("application/problem+json"));
    assertThat(r.body()).contains("sign-in-required");
    hasTheHeaders(r, "no-store");
    HttpResponse<String> reads = get("/api/v1/categories");
    assertThat(reads.statusCode()).isEqualTo(401);
    hasTheHeaders(reads, "no-store");
  }

  @Test
  void theRootAndTheFileNameGoToTheApp() throws Exception {
    for (String path : List.of("/", "/admin/admin.html")) {
      HttpResponse<String> r = get(path);
      assertThat(r.statusCode()).as(path).isEqualTo(302);
      assertThat(r.headers().allValues("Location")).as(path).containsExactly("/admin");
      hasTheHeaders(r, "no-store");
    }
  }

  @Test
  void aResourceLinkOpensOnThePublicSite() throws Exception {
    HttpResponse<String> r = get("/resource/9b2f6c1e-0000-4000-8000-000000000001/a-slug");
    assertThat(r.statusCode()).isEqualTo(302);
    assertThat(r.headers().allValues("Location"))
        .containsExactly(
            "https://public.example.org/resource/9b2f6c1e-0000-4000-8000-000000000001/a-slug");
    hasTheHeaders(r, "no-store");
  }

  @Test
  void aForwardedHostChangesNoRedirect() throws Exception {
    HttpResponse<String> r =
        send(HttpRequest.newBuilder(uri("/")).header("X-Forwarded-Host", "evil.example"));
    assertThat(r.headers().allValues("Location")).containsExactly("/admin");
  }

  String csrfToken() throws Exception {
    String cookie = csrfCookie(get("/api/v1/me"));
    return cookie.substring("__Host-XSRF-TOKEN=".length(), cookie.indexOf(';'));
  }

  static String csrfCookie(HttpResponse<?> r) {
    return r.headers().allValues("Set-Cookie").stream()
        .filter(c -> c.startsWith("__Host-XSRF-TOKEN="))
        .findFirst()
        .orElseThrow();
  }

  @Test
  void meWritesAHostOnlyCsrfCookieThatNoSiblingHostCanSet() throws Exception {
    HttpResponse<String> me = get("/api/v1/me");
    assertThat(me.statusCode()).isEqualTo(200);
    hasTheHeaders(me, "no-store");
    String cookie = csrfCookie(me);
    assertThat(cookie)
        .contains("Path=/")
        .contains("Secure")
        .contains("SameSite=Strict")
        .doesNotContain("HttpOnly")
        .doesNotContainIgnoringCase("Domain=");
    assertThat(me.headers().allValues("Set-Cookie")).noneMatch(c -> c.startsWith("XSRF-TOKEN="));
  }

  HttpResponse<String> adminWrite(HttpRequest.Builder b) throws Exception {
    return send(
        b.uri(uri("/api/v1/admin/category-backfill")).POST(HttpRequest.BodyPublishers.noBody()));
  }

  @Test
  void aWriteWithoutTheAdminHostsOriginIsRefusedBeforeAnythingElse() throws Exception {
    String token = csrfToken();
    HttpRequest.Builder valid =
        HttpRequest.newBuilder()
            .header("Cookie", "__Host-XSRF-TOKEN=" + token)
            .header("X-XSRF-TOKEN", token);
    HttpResponse<String> noOrigin = adminWrite(valid.copy());
    assertThat(noOrigin.statusCode()).isEqualTo(403);
    assertThat(noOrigin.body()).contains("must come from the admin host");
    hasTheHeaders(noOrigin, "no-store");
    HttpResponse<String> publicSite =
        adminWrite(valid.copy().header("Origin", "https://helper2.ai"));
    assertThat(publicSite.statusCode()).isEqualTo(403);
    assertThat(publicSite.body()).contains("must come from the admin host");
    HttpResponse<String> sameHost = adminWrite(valid.copy().header("Origin", origin()));
    assertThat(sameHost.statusCode()).as(sameHost.body()).isEqualTo(401);
  }

  @Test
  void theCsrfTokenCountsOnlyInTheHeaderAndOnlyFromTheHostCookie() throws Exception {
    String token = csrfToken();
    HttpResponse<String> form =
        send(
            HttpRequest.newBuilder(uri("/api/v1/admin/category-backfill"))
                .header("Origin", origin())
                .header("Cookie", "__Host-XSRF-TOKEN=" + token)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("_csrf=" + token)));
    assertThat(form.statusCode()).isEqualTo(403);
    assertThat(form.body()).contains("CSRF token");
    HttpResponse<String> planted =
        adminWrite(
            HttpRequest.newBuilder()
                .header("Origin", origin())
                .header("Cookie", "XSRF-TOKEN=planted")
                .header("X-XSRF-TOKEN", "planted"));
    assertThat(planted.statusCode()).isEqualTo(403);
    assertThat(planted.body()).contains("CSRF token");
  }

  static String sessionCookie(HttpResponse<?> r) {
    return r.headers().allValues("Set-Cookie").stream()
        .filter(c -> c.startsWith("__Host-SESSION="))
        .findFirst()
        .orElseThrow();
  }

  @Test
  void signInUsesThePinnedCallbackAndAHostSessionOfItsOwn() throws Exception {
    HttpResponse<String> r = get("/oauth2/authorization/test");
    assertThat(r.statusCode()).isEqualTo(302);
    assertThat(r.headers().firstValue("Location"))
        .hasValueSatisfying(l ->
            assertThat(l)
                .startsWith("https://provider.example/authorize")
                .contains("redirect_uri=https://admin.example.org/login/oauth2/code/test"));
    hasTheHeaders(r, "no-store");

    String cookie = sessionCookie(r);
    assertThat(cookie)
        .contains("Path=/")
        .contains("Secure")
        .contains("HttpOnly")
        .contains("SameSite=Lax")
        .doesNotContainIgnoringCase("Domain=");
    String id =
        new String(
            Base64.getDecoder()
                .decode(cookie.substring("__Host-SESSION=".length(), cookie.indexOf(';'))));
    assertThat(redis.hasKey("h2:v1:session:backoffice:sessions:" + id)).isTrue();
    assertThat(
            TestcontainersConfiguration.adminRedis().hasKey("h2:v1:session:public:sessions:" + id))
        .isFalse();
    assertThatThrownBy(() -> redis.hasKey("h2:v1:session:public:sessions:" + id))
        .hasStackTraceContaining("NOPERM");
  }

  @Test
  void aSessionCookieOfThePublicSiteIsNotReadHere() throws Exception {
    String cookie = sessionCookie(get("/oauth2/authorization/test"));
    String value = cookie.substring("__Host-SESSION=".length(), cookie.indexOf(';'));
    HttpResponse<String> me =
        send(HttpRequest.newBuilder(uri("/api/v1/me")).header("Cookie", "SESSION=" + value));
    assertThat(me.statusCode()).isEqualTo(200);
    assertThat(me.body()).contains("\"authenticated\":false");
    assertThat(me.headers().allValues("Set-Cookie")).noneMatch(c -> c.startsWith("SESSION="));
  }

  @Test
  void aFailedSignInReturnsToTheAdminApp() throws Exception {
    HttpResponse<String> r = get("/login/oauth2/code/test?code=c0de&state=unknown");
    assertThat(r.statusCode()).isEqualTo(302);
    assertThat(r.headers().allValues("Location")).containsExactly("/admin?signin=failed");
    hasTheHeaders(r, "no-store");
  }

  @Test
  void signOutIsAPostFromTheAdminHost() throws Exception {
    String token = csrfToken();
    HttpResponse<String> out =
        send(
            HttpRequest.newBuilder(uri("/logout"))
                .header("Origin", origin())
                .header("Cookie", "__Host-XSRF-TOKEN=" + token)
                .header("X-XSRF-TOKEN", token)
                .POST(HttpRequest.BodyPublishers.noBody()));
    assertThat(out.statusCode()).isEqualTo(204);
    HttpResponse<String> crossSite =
        send(
            HttpRequest.newBuilder(uri("/logout"))
                .header("Origin", "https://helper2.ai")
                .header("Cookie", "__Host-XSRF-TOKEN=" + token)
                .header("X-XSRF-TOKEN", token)
                .POST(HttpRequest.BodyPublishers.noBody()));
    assertThat(crossSite.statusCode()).isEqualTo(403);
  }
}
