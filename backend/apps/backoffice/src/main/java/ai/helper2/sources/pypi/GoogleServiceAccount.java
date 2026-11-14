package ai.helper2.sources.pypi;

import java.net.URI;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public record GoogleServiceAccount(String clientEmail, String privateKeyId, PrivateKey privateKey) {

  public static final URI TOKEN_URI = URI.create("https://oauth2.googleapis.com/token");

  private static final ObjectMapper JSON = new ObjectMapper();

  public static GoogleServiceAccount fromBase64Json(String base64) {
    JsonNode key = keyFile(base64);
    if (!"service_account".equals(key.path("type").asString(null))) {
      throw new IllegalArgumentException(
          "H2_PYPI_BIGQUERY_KEY: not a service account key file (type must be service_account)");
    }
    if (!TOKEN_URI.toString().equals(key.path("token_uri").asString(null))) {
      throw new IllegalArgumentException(
          "H2_PYPI_BIGQUERY_KEY: token_uri must be "
              + TOKEN_URI
              + ", the sign-in goes nowhere else");
    }
    return new GoogleServiceAccount(
        required(key, "client_email"),
        required(key, "private_key_id"),
        privateKeyOf(required(key, "private_key")));
  }

  @Override
  public String toString() {
    return "GoogleServiceAccount[" + clientEmail + ", key " + privateKeyId + "]";
  }

  private static JsonNode keyFile(String base64) {
    try {
      JsonNode key = JSON.readTree(Base64.getMimeDecoder().decode(base64.strip()));
      if (key == null || !key.isObject()) {
        throw new IllegalArgumentException("H2_PYPI_BIGQUERY_KEY: not a service account key file");
      }
      return key;
    } catch (IllegalArgumentException | JacksonException e) {
      throw new IllegalArgumentException(
          "H2_PYPI_BIGQUERY_KEY: not base64 of a service account JSON key file");
    }
  }

  private static String required(JsonNode key, String field) {
    String value = key.path(field).asString(null);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(
          "H2_PYPI_BIGQUERY_KEY: the service account key file has no " + field);
    }
    return value;
  }

  private static PrivateKey privateKeyOf(String pem) {
    String body =
        pem.replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replaceAll("\\s", "");
    try {
      return KeyFactory.getInstance("RSA")
          .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(body)));
    } catch (IllegalArgumentException | GeneralSecurityException e) {
      throw new IllegalArgumentException(
          "H2_PYPI_BIGQUERY_KEY: private_key is not an RSA key in PKCS#8 PEM form");
    }
  }
}
