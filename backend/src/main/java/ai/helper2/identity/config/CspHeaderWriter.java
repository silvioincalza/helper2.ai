package ai.helper2.identity.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.header.HeaderWriter;

/**
 * The Content Security Policy per request (ADR 0057): the strict policy on the admin port, the
 * public one (with the origins of the monetisation parts that are on) everywhere else.
 */
final class CspHeaderWriter implements HeaderWriter {

  static final String HEADER = "Content-Security-Policy";

  private final AdminPort adminPort;
  private final String publicCsp;

  CspHeaderWriter(AdminPort adminPort, String publicCsp) {
    this.adminPort = adminPort;
    this.publicCsp = publicCsp;
  }

  @Override
  public void writeHeaders(HttpServletRequest request, HttpServletResponse response) {
    if (!response.containsHeader(HEADER)) {
      response.setHeader(
          HEADER, adminPort.onAdminHost(request) ? SecurityConfiguration.CSP : publicCsp);
    }
  }
}
