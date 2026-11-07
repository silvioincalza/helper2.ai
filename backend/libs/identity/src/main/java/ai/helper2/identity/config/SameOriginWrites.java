package ai.helper2.identity.config;

import ai.helper2.platform.ProblemType;
import ai.helper2.platform.Problems;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.Set;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * On the admin host, a write must come from a page of the admin host itself (ADR 0035 §8). The
 * public site and the admin host are the same site for the browser, so SameSite cookies do not keep
 * them apart; the Origin header does. A write with no Origin, or another one, is refused before the
 * CSRF check. Browsers send Origin on every POST, PUT, PATCH and DELETE.
 */
final class SameOriginWrites extends OncePerRequestFilter {

  private static final Set<String> SAFE = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

  private final AdminPort adminPort;

  SameOriginWrites(AdminPort adminPort) {
    this.adminPort = adminPort;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return SAFE.contains(request.getMethod()) || !adminPort.onAdminHost(request);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (fromSameHost(request.getHeader("Origin"), request.getHeader("Host"))) {
      chain.doFilter(request, response);
      return;
    }
    Problems.write(response, ProblemType.FORBIDDEN, "This write must come from the admin host.");
  }

  /** The Origin names the host the request was sent to (host and port, any scheme). */
  static boolean fromSameHost(String origin, String host) {
    if (origin == null || host == null || origin.isBlank() || "null".equals(origin)) {
      return false;
    }
    try {
      String authority = URI.create(origin).getRawAuthority();
      return authority != null && authority.equalsIgnoreCase(host.strip());
    } catch (IllegalArgumentException malformed) {
      return false;
    }
  }
}
