package ai.helper2.sources.pypi;

import ai.helper2.search.SourceFailure;
import ai.helper2.sources.support.HttpClients;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

final class GoogleAccessTokens {

  static final String SCOPE = "https://www.googleapis.com/auth/bigquery";

  private static final Duration ASSERTION_LIFETIME = Duration.ofHours(1);
  private static final Duration RENEW_BEFORE_END = Duration.ofMinutes(5);
  private static final String JWT_BEARER = "urn:ietf:params:oauth:grant-type:jwt-bearer";
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Base64.Encoder BASE64_URL = Base64.getUrlEncoder().withoutPadding();

  private final GoogleServiceAccount account;
  private final RestClient http;
  private final Clock clock;
  private final URI tokenUri;

  private @Nullable String token;
  private Instant renewAt = Instant.MIN;

  GoogleAccessTokens(GoogleServiceAccount account, RestClient http, Clock clock) {
    this(account, http, clock, GoogleServiceAccount.TOKEN_URI);
  }

  GoogleAccessTokens(GoogleServiceAccount account, RestClient http, Clock clock, URI tokenUri) {
    this.account = account;
    this.http = http;
    this.clock = clock;
    this.tokenUri = tokenUri;
  }

  synchronized String current() {
    Instant now = clock.instant();
    if (token == null || !now.isBefore(renewAt)) {
      JsonNode answer = exchange(assertion(now));
      String accessToken = answer.path("access_token").asString(null);
      long seconds = answer.path("expires_in").asLong(0);
      if (accessToken == null || accessToken.isBlank() || seconds <= 0) {
        throw new SourceFailure.Unavailable("malformed response: no access token");
      }
      token = accessToken;
      renewAt = now.plusSeconds(seconds).minus(RENEW_BEFORE_END);
    }
    return token;
  }

  private JsonNode exchange(String assertion) {
    String form =
        "grant_type="
            + URLEncoder.encode(JWT_BEARER, StandardCharsets.UTF_8)
            + "&assertion="
            + URLEncoder.encode(assertion, StandardCharsets.UTF_8);
    String body =
        HttpClients.getText(
            () ->
                http.post()
                    .uri(tokenUri)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(String.class),
            clock);
    try {
      return JSON.readTree(body);
    } catch (JacksonException e) {
      throw new SourceFailure.Unavailable("malformed response: token answer is not JSON");
    }
  }

  private String assertion(Instant now) {
    Map<String, Object> header = new LinkedHashMap<>();
    header.put("alg", "RS256");
    header.put("typ", "JWT");
    header.put("kid", account.privateKeyId());
    Map<String, Object> claims = new LinkedHashMap<>();
    claims.put("iss", account.clientEmail());
    claims.put("scope", SCOPE);
    claims.put("aud", GoogleServiceAccount.TOKEN_URI.toString());
    claims.put("iat", now.getEpochSecond());
    claims.put("exp", now.plus(ASSERTION_LIFETIME).getEpochSecond());
    String signed = part(header) + "." + part(claims);
    return signed + "." + BASE64_URL.encodeToString(sign(signed));
  }

  private byte[] sign(String content) {
    try {
      Signature rsa = Signature.getInstance("SHA256withRSA");
      rsa.initSign(account.privateKey());
      rsa.update(content.getBytes(StandardCharsets.US_ASCII));
      return rsa.sign();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("cannot sign the Google sign-in assertion", e);
    }
  }

  private static String part(Map<String, Object> json) {
    return BASE64_URL.encodeToString(JSON.writeValueAsBytes(json));
  }
}
