package ai.helper2.identity.config;

import ai.helper2.platform.ProblemType;
import ai.helper2.platform.Problems;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The parts both apps' security filter chains share (ADR 0032). Each app builds its own chain: the
 * public app's in apps/public, the back office's in apps/backoffice. Anonymous use is the default
 * on the public site; sign-in (OAuth2/OIDC, any configured provider) adds server-side bookmarks.
 * ADMIN comes from an explicit allow-list, "registrationId:subject" (GitHub: numeric id, never the
 * login), or "registrationId:email" for OIDC providers when the email is verified, and only on the
 * back office.
 */
public final class WebSecurity {

  private WebSecurity() {}

  /**
   * The site uses no camera, microphone, location, payment or USB: no page, and no script on it,
   * may ask.
   */
  public static final String PERMISSIONS_POLICY =
      "camera=(), microphone=(), geolocation=(), payment=(), usb=()";

  /**
   * On a deployment that must stay out of search engines (staging), every response says so. A
   * header and not a robots.txt Disallow: a crawler must fetch the page to see the noindex.
   */
  public static final StaticHeadersWriter NOINDEX =
      new StaticHeadersWriter("X-Robots-Tag", "noindex, nofollow");

  /** Every API path: the paths that answer with a problem instead of a page. */
  public static final RequestMatcher API =
      PathPatternRequestMatcher.withDefaults().matcher("/api/**");

  /**
   * A refused API request gets the problem the contract promises, not a bare 401 or the container's
   * error page (which echoes the path and a timestamp) for a 403.
   */
  public static void apiProblems(HttpSecurity http) throws Exception {
    http.exceptionHandling(e ->
        e.defaultAuthenticationEntryPointFor(
                (req, res, ex) ->
                    Problems.write(res, ProblemType.SIGN_IN_REQUIRED, "Sign in to do this."),
                API)
            .defaultAccessDeniedHandlerFor(
                (req, res, ex) -> Problems.write(res, ProblemType.FORBIDDEN, deniedWhy(ex)), API));
  }

  /** A missing or wrong CSRF token is the one 403 the client can fix by itself. */
  public static String deniedWhy(AccessDeniedException ex) {
    return ex instanceof CsrfException
        ? "The CSRF token is missing or wrong."
        : "This account may not do this.";
  }

  /**
   * The signed-in user with ROLE_USER, and ROLE_ADMIN when [grantsAdmin] and the user is on the
   * allow-list. Only the back office grants ADMIN; the public app never does (ADR 0024, 0032).
   */
  public static OAuth2UserService<OAuth2UserRequest, OAuth2User> oauthUsers(
      Set<String> admins, boolean grantsAdmin) {
    DefaultOAuth2UserService delegate = new DefaultOAuth2UserService();
    return req -> {
      OAuth2User user = delegate.loadUser(req);
      String reg = req.getClientRegistration().getRegistrationId();
      String key =
          req.getClientRegistration()
              .getProviderDetails()
              .getUserInfoEndpoint()
              .getUserNameAttributeName();
      // Match only the immutable subject (GitHub: numeric id). Logins can be renamed and
      // re-registered.
      Set<GrantedAuthority> roles = roles(admins, reg, user.getName(), null, grantsAdmin);
      return new DefaultOAuth2User(
          roles, user.getAttributes(), key == null || key.isBlank() ? "id" : key);
    };
  }

  /** As {@link #oauthUsers}, for OIDC providers (a verified email may be on the allow-list). */
  public static OAuth2UserService<OidcUserRequest, OidcUser> oidcUsers(
      Set<String> admins, boolean grantsAdmin) {
    OidcUserService delegate = new OidcUserService();
    return req -> {
      OidcUser user = delegate.loadUser(req);
      String reg = req.getClientRegistration().getRegistrationId();
      Boolean verified = user.getEmailVerified();
      String email = Boolean.TRUE.equals(verified) ? user.getEmail() : null;
      return new DefaultOidcUser(
          roles(admins, reg, user.getSubject(), email, grantsAdmin),
          user.getIdToken(),
          user.getUserInfo());
    };
  }

  static Set<GrantedAuthority> roles(
      Set<String> admins,
      String registrationId,
      String subject,
      String alias,
      boolean grantsAdmin) {
    Set<GrantedAuthority> roles = new HashSet<>();
    roles.add(new SimpleGrantedAuthority("ROLE_USER"));
    boolean allowListed =
        admins.contains(registrationId + ":" + subject)
            || (alias != null && admins.contains(registrationId + ":" + alias));
    if (grantsAdmin && allowListed) {
      roles.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }
    return roles;
  }

  /**
   * Checks the raw cookie value in the {@code X-XSRF-TOKEN} header, the only place the web apps put
   * it (ADR 0024 (part was 0035) §8). Never a form parameter: a form from another page of the same
   * site can send one, with a cookie that page planted for the whole domain, but it cannot set a
   * header. The token is never written into a page, so it needs no per-response mask (BREACH): the
   * request attribute holds the raw value, the same the cookie and the header carry. Unlike
   * Spring's SPA handler it never loads the token itself, so a response only gets the cookie when
   * something asks for it.
   */
  public static final class HeaderOnlyCsrfTokenHandler implements CsrfTokenRequestHandler {
    private final CsrfTokenRequestHandler plain = new CsrfTokenRequestAttributeHandler();

    @Override
    public void handle(
        jakarta.servlet.http.HttpServletRequest request,
        jakarta.servlet.http.HttpServletResponse response,
        Supplier<CsrfToken> csrfToken) {
      plain.handle(request, response, csrfToken);
    }

    @Override
    public String resolveCsrfTokenValue(
        jakarta.servlet.http.HttpServletRequest request, CsrfToken csrfToken) {
      String header = request.getHeader(csrfToken.getHeaderName());
      return StringUtils.hasText(header) ? header : null;
    }
  }

  /**
   * Forces the deferred CSRF token to load on {@code GET /api/v1/me}, which both web apps call when
   * they start, so the CSRF cookie exists before their first POST. Only there: on every other
   * response (static files, the public cacheable GETs) a Set-Cookie would stop a CDN from caching
   * it, and could share one token between visitors. A POST that comes without the cookie is refused
   * (403) and gets a fresh cookie with the refusal.
   */
  public static final class CsrfCookieFilter extends OncePerRequestFilter {
    public static final String WRITES_COOKIE = "/api/v1/me";

    @Override
    protected boolean shouldNotFilter(jakarta.servlet.http.HttpServletRequest req) {
      return !("GET".equals(req.getMethod()) && WRITES_COOKIE.equals(req.getRequestURI()));
    }

    @Override
    protected void doFilterInternal(
        jakarta.servlet.http.HttpServletRequest req,
        jakarta.servlet.http.HttpServletResponse res,
        jakarta.servlet.FilterChain chain)
        throws jakarta.servlet.ServletException, java.io.IOException {
      Object token = req.getAttribute(CsrfToken.class.getName());
      if (token instanceof CsrfToken t) {
        t.getToken();
      }
      chain.doFilter(req, res);
    }
  }
}
