package ai.searchavo.site.csp;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.function.Supplier;
import org.springframework.security.web.header.HeaderWriter;

final class CspHeaderWriter implements HeaderWriter {

  static final String HEADER = "Content-Security-Policy";

  private final Supplier<String> currentCsp;

  CspHeaderWriter(Supplier<String> currentCsp) {
    this.currentCsp = currentCsp;
  }

  private String policyFor(HttpServletRequest request) {
    String publicCsp = currentCsp.get();
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
