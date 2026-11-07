package ai.helper2.identity.config;

import ai.helper2.platform.CspNonce;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.header.HeaderWriter;

/**
 * The public site's Content Security Policy per request (ADR 0030): the origins of the monetisation
 * parts that are on, or the AdSense nonce policy with this request's nonce (ADR 0030).
 */
final class CspHeaderWriter implements HeaderWriter {

  static final String HEADER = "Content-Security-Policy";

  private final String publicCsp;

  CspHeaderWriter(String publicCsp) {
    this.publicCsp = publicCsp;
  }

  private String policyFor(HttpServletRequest request) {
    return publicCsp.contains(ContentSecurityPolicy.NONCE)
        ? publicCsp.replace(ContentSecurityPolicy.NONCE, CspNonce.of(request))
        : publicCsp;
  }

  @Override
  public void writeHeaders(HttpServletRequest request, HttpServletResponse response) {
    if (!response.containsHeader(HEADER)) {
      response.setHeader(HEADER, policyFor(request));
    }
  }
}
