package ai.helper2.site.csp;

import jakarta.servlet.http.HttpServletRequest;
import java.security.SecureRandom;
import java.util.Base64;

public final class CspNonce {

  private static final String ATTRIBUTE = CspNonce.class.getName();
  private static final SecureRandom RANDOM = new SecureRandom();

  private CspNonce() {}

  public static String of(HttpServletRequest request) {
    if (request.getAttribute(ATTRIBUTE) instanceof String nonce) {
      return nonce;
    }
    byte[] bytes = new byte[18];
    RANDOM.nextBytes(bytes);
    String nonce = Base64.getEncoder().encodeToString(bytes);
    request.setAttribute(ATTRIBUTE, nonce);
    return nonce;
  }
}
