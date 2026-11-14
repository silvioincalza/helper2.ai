package ai.helper2.identity.security;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.TestcontainersConfiguration;
import ai.helper2.testing.FakeSignInProvider;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "logging.structured.format.console=",
      "helper2.identity.admins=" + SessionWithoutAccountIT.ADMIN,
    })
@Import(TestcontainersConfiguration.class)
class SessionWithoutAccountIT {

  static final String ADMIN = FakeSignInProvider.REGISTRATION + ":" + FakeSignInProvider.USER_ID;
  static final String SUBJECT = String.valueOf(FakeSignInProvider.USER_ID);
  static final String SESSIONS = "h2:v1:session:backoffice:*";
  static final FakeSignInProvider PROVIDER = FakeSignInProvider.start();

  @DynamicPropertySource
  static void provider(DynamicPropertyRegistry registry) {
    PROVIDER.register(registry);
  }

  @AfterAll
  static void stop() {
    PROVIDER.close();
  }

  @LocalServerPort int port;

  @Autowired JdbcClient jdbc;

  final StringRedisTemplate redis = TestcontainersConfiguration.adminRedis();
  final HttpClient http = HttpClient.newHttpClient();

  String origin() {
    return "http://localhost:" + port;
  }

  long accounts() {
    return jdbc.sql("SELECT count(*) FROM app_user WHERE provider = :p AND subject = :s")
        .param("p", FakeSignInProvider.REGISTRATION)
        .param("s", SUBJECT)
        .query(Long.class)
        .single();
  }

  long auditEntries() {
    return jdbc.sql("SELECT count(*) FROM admin_audit WHERE admin_id = :a")
        .param("a", SUBJECT)
        .query(Long.class)
        .single();
  }

  void theDatabaseLosesTheAccount() {
    jdbc.sql("DELETE FROM app_user WHERE provider = :p AND subject = :s")
        .param("p", FakeSignInProvider.REGISTRATION)
        .param("s", SUBJECT)
        .update();
  }

  HttpResponse<String> get(String path, String cookies) throws Exception {
    return http.send(
        HttpRequest.newBuilder(URI.create(origin() + path)).header("Cookie", cookies).build(),
        HttpResponse.BodyHandlers.ofString());
  }

  HttpResponse<String> changeASetting(String cookies, String csrfToken) throws Exception {
    return http.send(
        HttpRequest.newBuilder(
                URI.create(origin() + "/api/v1/admin/runtime-settings/scout.gapDailyCalls"))
            .header("Cookie", cookies)
            .header("Origin", origin())
            .header("X-XSRF-TOKEN", csrfToken)
            .header("Content-Type", "application/json")
            .PUT(
                HttpRequest.BodyPublishers.ofString("{\"value\":-1,\"version\":0,\"reason\":null}"))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  static String csrfTokenOf(HttpResponse<String> me) {
    return me.headers().allValues("Set-Cookie").stream()
        .filter(c -> c.startsWith("__Host-XSRF-TOKEN="))
        .map(c -> c.substring("__Host-XSRF-TOKEN=".length(), c.indexOf(';')))
        .findFirst()
        .orElseThrow();
  }

  @Test
  void anAdminSessionWithoutAnAccountIsEndedBeforeItWritesAnything() throws Exception {
    redis.delete(redis.keys(SESSIONS));
    FakeSignInProvider.SignedIn session = PROVIDER.signIn(URI.create(origin()));
    assertThat(accounts()).as("the sign-in writes the account").isOne();
    HttpResponse<String> me = get("/api/v1/me", session.cookieHeader());
    assertThat(me.body()).contains("\"authenticated\":true").contains("ADMIN");
    String csrfToken = csrfTokenOf(me);
    String cookies = session.cookieHeader() + "; __Host-XSRF-TOKEN=" + csrfToken;
    assertThat(get("/api/v1/admin/runtime-settings", cookies).statusCode()).isEqualTo(200);
    long auditedBefore = auditEntries();

    theDatabaseLosesTheAccount();
    HttpResponse<String> refused = changeASetting(cookies, csrfToken);

    assertThat(refused.statusCode()).isEqualTo(401);
    assertThat(refused.headers().firstValue("Content-Type").orElseThrow())
        .startsWith("application/problem+json");
    assertThat(refused.body()).contains("\"code\":\"sign-in-again\"");
    assertThat(refused.headers().allValues("Set-Cookie"))
        .as("the session cookie is cleared")
        .anyMatch(c -> c.startsWith("__Host-SESSION=;") && c.contains("Max-Age=0"));
    assertThat(redis.keys(SESSIONS)).as("the session is gone on the server").isEmpty();
    assertThat(auditEntries())
        .as("nothing is audited for an unknown user")
        .isEqualTo(auditedBefore);
    assertThat(accounts()).as("no request writes the account again").isZero();

    assertThat(get("/api/v1/me", cookies).body()).contains("\"authenticated\":false");
    assertThat(get("/api/v1/admin/runtime-settings", cookies).body())
        .contains("\"code\":\"sign-in-required\"");
    assertThat(accounts()).isZero();
  }
}
