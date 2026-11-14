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
    properties = "logging.structured.format.console=")
@Import(TestcontainersConfiguration.class)
class SessionWithoutAccountIT {

  static final FakeSignInProvider PROVIDER = FakeSignInProvider.start();
  static final String SESSIONS = "h2:v1:session:public:*";

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

  long accounts() {
    return jdbc.sql("SELECT count(*) FROM app_user WHERE provider = :p AND subject = :s")
        .param("p", FakeSignInProvider.REGISTRATION)
        .param("s", String.valueOf(FakeSignInProvider.USER_ID))
        .query(Long.class)
        .single();
  }

  void theDatabaseLosesTheAccount() {
    jdbc.sql("DELETE FROM app_user WHERE provider = :p AND subject = :s")
        .param("p", FakeSignInProvider.REGISTRATION)
        .param("s", String.valueOf(FakeSignInProvider.USER_ID))
        .update();
  }

  HttpResponse<String> get(String path, FakeSignInProvider.SignedIn session) throws Exception {
    return http.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Cookie", session.cookieHeader())
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void aSessionWithoutAnAccountIsEndedAndToldToSignInAgain() throws Exception {
    redis.delete(redis.keys(SESSIONS));
    FakeSignInProvider.SignedIn session = PROVIDER.signIn(URI.create("http://localhost:" + port));
    assertThat(accounts()).as("the sign-in writes the account").isOne();
    assertThat(get("/api/v1/me", session).body()).contains("\"authenticated\":true");

    theDatabaseLosesTheAccount();

    assertThat(get("/api/v1/policy", session).statusCode())
        .as("a page that needs no account still answers")
        .isEqualTo(200);
    assertThat(accounts()).as("no request writes the account again").isZero();

    HttpResponse<String> refused = get("/api/v1/me", session);

    assertThat(refused.statusCode()).isEqualTo(401);
    assertThat(refused.headers().firstValue("Content-Type").orElseThrow())
        .startsWith("application/problem+json");
    assertThat(refused.body())
        .contains("\"code\":\"sign-in-again\"")
        .contains("https://helper2.ai/problems/sign-in-again");
    assertThat(refused.headers().allValues("Set-Cookie"))
        .as("the session cookie is cleared")
        .anyMatch(c -> c.startsWith("SESSION=;") && c.contains("Max-Age=0"));
    assertThat(redis.keys(SESSIONS)).as("the session is gone on the server").isEmpty();
    assertThat(accounts()).isZero();

    HttpResponse<String> after = get("/api/v1/me", session);

    assertThat(after.statusCode()).isEqualTo(200);
    assertThat(after.body()).contains("\"authenticated\":false");
    assertThat(get("/api/v1/me/bookmarks", session).body())
        .contains("\"code\":\"sign-in-required\"");
    assertThat(accounts()).isZero();
  }
}
