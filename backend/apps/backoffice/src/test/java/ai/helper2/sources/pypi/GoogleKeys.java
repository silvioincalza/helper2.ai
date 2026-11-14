package ai.helper2.sources.pypi;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

final class GoogleKeys {

  static final KeyPair PAIR = generate();

  private GoogleKeys() {}

  static String keyJson(String tokenUri) {
    String pem =
        "-----BEGIN PRIVATE KEY-----\n"
            + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
                .encodeToString(PAIR.getPrivate().getEncoded())
            + "\n-----END PRIVATE KEY-----\n";
    return """
    {"type": "service_account", "project_id": "helper2ai",
     "private_key_id": "key-1", "private_key": "%s",
     "client_email": "scout@helper2ai.iam.gserviceaccount.com",
     "token_uri": "%s"}
    """
        .formatted(pem.replace("\n", "\\n"), tokenUri);
  }

  static String base64(String json) {
    return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
  }

  private static KeyPair generate() {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      return generator.generateKeyPair();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
