package ai.helper2.api;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.TestcontainersConfiguration;
import ai.helper2.testing.SourcesOff;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

@SourcesOff
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
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
@Import({TestcontainersConfiguration.class, RedisDownIT.UnreachableRedis.class})
class RedisDownIT {

  @TestConfiguration(proxyBeanMethods = false)
  static class UnreachableRedis {
    @Bean
    @Primary
    DataRedisConnectionDetails unreachableRedis() throws IOException {
      int closedPort;
      try (ServerSocket socket = new ServerSocket(0)) {
        closedPort = socket.getLocalPort();
      }
      DataRedisConnectionDetails.Standalone standalone =
          DataRedisConnectionDetails.Standalone.of("localhost", closedPort);
      return new DataRedisConnectionDetails() {
        @Override
        public Standalone getStandalone() {
          return standalone;
        }
      };
    }
  }

  @LocalServerPort int port;

  @Autowired MeterRegistry meters;

  final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  HttpResponse<String> get(String path) throws Exception {
    return getAt(port, path);
  }

  HttpResponse<String> getAt(int port, String path) throws Exception {
    return http.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .timeout(Duration.ofSeconds(10))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void anonymousSearchWorksWithoutASessionAndRateLimitsCountInMemory() throws Exception {
    List<HttpResponse<String>> responses =
        List.of(get("/api/v1/search?q=redis+down"), get("/api/v1/me"), get("/api/v1/recent"));

    assertThat(responses).extracting(HttpResponse::statusCode).containsExactly(200, 200, 200);
    assertThat(responses)
        .allSatisfy(r ->
            assertThat(r.headers().allValues("Set-Cookie"))
                .noneMatch(c -> c.startsWith("SESSION=")));
    assertThat(meters.counter("helper2.rate-limit.fallback", "limiter", "search").count())
        .isPositive();
  }

  @Test
  void aRequestWithASessionCookieIsServedAsAnonymous() throws Exception {
    for (String path : List.of("/api/v1/search?q=signed+in", "/api/v1/me")) {
      HttpResponse<String> r =
          http.send(
              HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                  .header("Cookie", "SESSION=ZjYxYjM0ZjctMmQ5Zi00YjY0LWI0YjYtNmE1ZTQ1ZDc1ZDA5")
                  .timeout(Duration.ofSeconds(10))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertThat(r.statusCode()).as(path).isEqualTo(200);
      if (path.equals("/api/v1/me")) {
        assertThat(r.body()).contains("\"authenticated\":false");
      }
    }
  }

  @Test
  void signInFailsAtOnceAndReturnsToTheSite() throws Exception {
    long start = System.nanoTime();
    HttpResponse<String> r = get("/oauth2/authorization/test");

    assertThat(r.statusCode()).isEqualTo(302);
    assertThat(r.headers().firstValue("Location"))
        .hasValueSatisfying(l -> assertThat(l).endsWith("/?signin=failed"));
    assertThat(r.headers().allValues("Set-Cookie")).noneMatch(c -> c.startsWith("SESSION="));
    assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
  }
}
