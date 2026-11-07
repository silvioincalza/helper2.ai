package ai.helper2.identity.config;

import ai.helper2.platform.ProblemType;
import ai.helper2.platform.Problems;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The admin host's edge (ADR 0035, 0069): which paths answer, the security headers and the cache
 * rule of every response, written before anything else runs, so a refusal, a redirect and an error
 * carry them too.
 *
 * <ul>
 *   <li>Answered: the admin web app ({@code /admin}, {@code /admin/**}), {@code /} and {@code
 *       /resource/**} (redirects), {@code /api/v1/admin/**}, {@code GET /api/v1/me}, the reads the
 *       admin pages make ({@code GET /api/v1/search|sources|categories}), the sign-in ({@code
 *       /oauth2/**}, {@code /login/oauth2/**}), {@code POST /logout} and the actuator. Anything
 *       else is a plain 404 problem, without the path.
 *   <li>Cache: the hashed files under {@code /admin/assets/} are private and kept a year; the other
 *       files of the web app are revalidated; everything else (pages, the API, redirects, errors)
 *       is never stored. No later code changes it.
 * </ul>
 */
final class BackOfficeEdge extends OncePerRequestFilter {

  /** A fixed policy: no nonce, no third-party origin. The admin shell has no inline script. */
  static final String CSP =
      "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; "
          + "connect-src 'self'; font-src 'self'; object-src 'none'; base-uri 'none'; "
          + "form-action 'self'; frame-ancestors 'none'; frame-src 'none'; worker-src 'none'";

  static final Map<String, String> HEADERS =
      Map.of(
          "Content-Security-Policy", CSP,
          "X-Frame-Options", "DENY",
          "X-Content-Type-Options", "nosniff",
          "Referrer-Policy", "no-referrer",
          "Permissions-Policy", WebSecurity.PERMISSIONS_POLICY,
          "Cross-Origin-Opener-Policy", "same-origin",
          "Cross-Origin-Resource-Policy", "same-origin",
          // Whatever H2_NOINDEX says: the admin host is never for search engines.
          "X-Robots-Tag", "noindex, nofollow",
          // Behind the ingress the request is plain HTTP; the browser sees HTTPS.
          "Strict-Transport-Security", "max-age=31536000");

  static final String NO_STORE = "no-store";
  static final String ASSETS = "private, max-age=31536000, immutable";
  static final String REVALIDATE = "no-cache";

  private static final String NOT_FOUND = Problems.json(ProblemType.NOT_FOUND, null);

  private static final Set<String> ADMIN_READS =
      Set.of("/api/v1/me", "/api/v1/search", "/api/v1/sources", "/api/v1/categories");

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String path = path(request);
    HEADERS.forEach(response::setHeader);
    if (!answers(request.getMethod(), path)) {
      // A refusal is never cached, not even on an asset path.
      response.setHeader("Cache-Control", NO_STORE);
      response.setStatus(HttpServletResponse.SC_NOT_FOUND);
      response.setContentType("application/problem+json");
      response.getWriter().write(NOT_FOUND);
      return;
    }
    response.setHeader("Cache-Control", cacheControl(path));
    chain.doFilter(request, new FixedCacheControl(response));
  }

  /**
   * The decoded, normalised path the container maps (servlet path + path info), so "%61dmin" or
   * "x/../admin" cannot pass as another path.
   */
  static String path(HttpServletRequest request) {
    return request.getServletPath() + Optional.ofNullable(request.getPathInfo()).orElse("");
  }

  static boolean answers(String method, String path) {
    boolean read = "GET".equals(method) || "HEAD".equals(method);
    if (under(path, "/api/v1/admin")) {
      return true;
    }
    if (read) {
      // The actuator is read-only here: health and metrics, no write endpoint.
      return path.equals("/")
          || under(path, "/actuator")
          || under(path, "/admin")
          || under(path, "/resource")
          || ADMIN_READS.contains(path)
          || under(path, "/oauth2")
          || under(path, "/login/oauth2");
    }
    return "POST".equals(method) && path.equals("/logout");
  }

  static String cacheControl(String path) {
    if (path.startsWith("/admin/assets/")) {
      return ASSETS;
    }
    if (path.startsWith("/admin/") && hasExtension(path) && !path.endsWith(".html")) {
      return REVALIDATE;
    }
    return NO_STORE;
  }

  private static boolean hasExtension(String path) {
    return path.substring(path.lastIndexOf('/') + 1).contains(".");
  }

  private static boolean under(String path, String root) {
    return path.equals(root) || path.startsWith(root + "/");
  }

  /** The cache rule is the edge's: a controller's or a resource handler's own is ignored. */
  static final class FixedCacheControl extends HttpServletResponseWrapper {
    FixedCacheControl(HttpServletResponse response) {
      super(response);
    }

    @Override
    public void setHeader(String name, String value) {
      if (!isCacheHeader(name)) {
        super.setHeader(name, value);
      }
    }

    @Override
    public void addHeader(String name, String value) {
      if (!isCacheHeader(name)) {
        super.addHeader(name, value);
      }
    }

    @Override
    public void setDateHeader(String name, long date) {
      if (!isCacheHeader(name)) {
        super.setDateHeader(name, date);
      }
    }

    @Override
    public void addDateHeader(String name, long date) {
      if (!isCacheHeader(name)) {
        super.addDateHeader(name, date);
      }
    }

    /** A refusal or an error is never stored, whatever file was asked for. */
    @Override
    public void setStatus(int status) {
      super.setStatus(status);
      noStoreOnError(status);
    }

    @Override
    public void sendError(int status) throws IOException {
      noStoreOnError(status);
      super.sendError(status);
    }

    @Override
    public void sendError(int status, String message) throws IOException {
      noStoreOnError(status);
      super.sendError(status, message);
    }

    private void noStoreOnError(int status) {
      if (status >= 400) {
        ((HttpServletResponse) getResponse()).setHeader("Cache-Control", NO_STORE);
      }
    }

    private static boolean isCacheHeader(String name) {
      return "Cache-Control".equalsIgnoreCase(name)
          || "Expires".equalsIgnoreCase(name)
          || "Pragma".equalsIgnoreCase(name);
    }
  }
}
