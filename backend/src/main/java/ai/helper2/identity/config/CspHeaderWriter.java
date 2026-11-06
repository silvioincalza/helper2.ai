package ai.helper2.identity.config;

import ai.helper2.platform.CspNonce;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.header.HeaderWriter;

/**
 * The Content Security Policy per request (ADR 0057): the strict policy on the admin port, the
 * public one (with the origins of the monetisation parts that are on, or the AdSense nonce policy
 * with this request's nonce, ADR 0059) everywhere else.
 */
final class CspHeaderWriter implements HeaderWriter {

  static final String HEADER = "Content-Security-Policy";

  private final AdminPort adminPort;
  private final String publicCsp;

  CspHeaderWriter(AdminPort adminPort, String publicCsp) {
    this.adminPort = adminPort;
    this.publicCsp = publicCsp;
  }

  private String policyFor(HttpServletRequest request) {
    if (adminPort.onAdminHost(request)) {
      return SecurityConfiguration.CSP;
    }
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
