package ai.helper2.platform;

import jakarta.servlet.http.HttpServletRequest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * One Content Security Policy nonce per request (ADR 0030): the pages module writes it on every
 * script of the page it fills, and the CSP header names it. Whichever asks first creates it; both
 * then read the same value from the request.
 */
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
