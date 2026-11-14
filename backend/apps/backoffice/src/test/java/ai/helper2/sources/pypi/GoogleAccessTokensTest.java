package ai.helper2.sources.pypi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.helper2.search.SourceFailure;
import ai.helper2.testing.FixtureServer;
import ai.helper2.testing.TestHttp;
import io.micrometer.observation.ObservationRegistry;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class GoogleAccessTokensTest {

  static final Instant NOW = Instant.parse("2026-11-09T12:00:00Z");

  final FixtureServer server = new FixtureServer();
  final AtomicReference<Instant> now = new AtomicReference<>(NOW);
  final Clock clock =
      new Clock() {
        @Override
        public ZoneOffset getZone() {
          return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
          return this;
        }

        @Override
        public Instant instant() {
          return now.get();
        }
      };

  @AfterEach
  void stop() {
    server.close();
  }

  private GoogleAccessTokens tokens() {
    GoogleServiceAccount account =
        GoogleServiceAccount.fromBase64Json(
            GoogleKeys.base64(GoogleKeys.keyJson(GoogleServiceAccount.TOKEN_URI.toString())));
    return new GoogleAccessTokens(
        account,
        TestHttp.restClientBuilder(Duration.ofSeconds(2), ObservationRegistry.NOOP).build(),
        clock,
        URI.create(server.baseUrl() + "/token"));
  }

  @Test
  void aSignedAssertionIsSwappedForAnAccessToken() throws Exception {
    server.route(
        "/token",
        FixtureServer.Reply.ok(
            "{\"access_token\":\"ya29.token\",\"expires_in\":3599,\"token_type\":\"Bearer\"}"));

    assertThat(tokens().current()).isEqualTo("ya29.token");

    Map<String, String> form = form(server.requestBodies.getFirst());
    assertThat(form).containsEntry("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer");
    String[] jwt = form.get("assertion").split("\\.");
    JsonNode header = decode(jwt[0]);
    JsonNode claims = decode(jwt[1]);
    assertThat(header.path("alg").asString()).isEqualTo("RS256");
    assertThat(header.path("kid").asString()).isEqualTo("key-1");
    assertThat(claims.path("iss").asString()).isEqualTo("scout@helper2ai.iam.gserviceaccount.com");
    assertThat(claims.path("aud").asString()).isEqualTo("https://oauth2.googleapis.com/token");
    assertThat(claims.path("scope").asString()).isEqualTo(GoogleAccessTokens.SCOPE);
    assertThat(claims.path("iat").asLong()).isEqualTo(NOW.getEpochSecond());
    assertThat(claims.path("exp").asLong()).isEqualTo(NOW.getEpochSecond() + 3600);
    Signature rsa = Signature.getInstance("SHA256withRSA");
    rsa.initVerify(GoogleKeys.PAIR.getPublic());
    rsa.update((jwt[0] + "." + jwt[1]).getBytes(StandardCharsets.US_ASCII));
    assertThat(rsa.verify(Base64.getUrlDecoder().decode(jwt[2]))).isTrue();
  }

  @Test
  void aTokenIsReusedUntilFiveMinutesBeforeItEnds() {
    server.route(
        "/token", FixtureServer.Reply.ok("{\"access_token\":\"ya29.token\",\"expires_in\":3600}"));
    GoogleAccessTokens tokens = tokens();

    tokens.current();
    now.set(NOW.plus(Duration.ofMinutes(54)));
    tokens.current();
    assertThat(server.requests).hasSize(1);

    now.set(NOW.plus(Duration.ofMinutes(56)));
    tokens.current();
    assertThat(server.requests).hasSize(2);
  }

  @Test
  void aRefusedSignInIsASourceFailureThatDoesNotShowTheAssertion() {
    server.route(
        "/token", new FixtureServer.Reply(400, "{\"error\":\"invalid_grant\"}", Map.of(), 0));

    assertThatThrownBy(() -> tokens().current())
        .isInstanceOf(SourceFailure.class)
        .hasMessageNotContaining("eyJ");
  }

  @Test
  void anAnswerWithoutATokenIsAMalformedResponse() {
    server.route("/token", FixtureServer.Reply.ok("{\"expires_in\":3600}"));

    assertThatThrownBy(() -> tokens().current())
        .isInstanceOf(SourceFailure.Unavailable.class)
        .hasMessageContaining("malformed");
  }

  private static Map<String, String> form(String body) {
    java.util.Map<String, String> fields = new java.util.HashMap<>();
    for (String pair : body.split("&")) {
      String[] kv = pair.split("=", 2);
      fields.put(
          URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
          URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
    }
    return fields;
  }

  private static JsonNode decode(String part) {
    return new ObjectMapper().readTree(Base64.getUrlDecoder().decode(part));
  }
}
