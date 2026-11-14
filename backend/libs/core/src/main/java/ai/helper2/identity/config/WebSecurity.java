package ai.helper2.identity.config;

import ai.helper2.platform.ProblemType;
import ai.helper2.platform.Problems;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
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

public final class WebSecurity {

  private WebSecurity() {}

  public static final String PERMISSIONS_POLICY =
      "camera=(), microphone=(), geolocation=(), payment=(), usb=()";

  public static final StaticHeadersWriter NOINDEX =
      new StaticHeadersWriter("X-Robots-Tag", "noindex, nofollow");

  public static final RequestMatcher API =
      PathPatternRequestMatcher.withDefaults().matcher("/api/**");

  public static void apiProblems(HttpSecurity http) throws Exception {
    http.exceptionHandling(e ->
        e.defaultAuthenticationEntryPointFor(
                (req, res, ex) ->
                    Problems.write(res, ProblemType.SIGN_IN_REQUIRED, "Sign in to do this."),
                API)
            .defaultAccessDeniedHandlerFor(
                (req, res, ex) -> Problems.write(res, ProblemType.FORBIDDEN, deniedWhy(ex)), API));
  }

  public static String deniedWhy(AccessDeniedException ex) {
    return ex instanceof CsrfException
        ? "The CSRF token is missing or wrong."
        : "This account may not do this.";
  }

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
      Set<GrantedAuthority> roles = roles(admins, reg, user.getName(), null, grantsAdmin);
      String nameKey = key == null || key.isBlank() ? "id" : key;
      return new DefaultOAuth2User(roles, keptAttributes(user.getAttributes(), nameKey), nameKey);
    };
  }

  static Map<String, Object> keptAttributes(Map<String, Object> reply, String nameKey) {
    Map<String, Object> kept = new LinkedHashMap<>();
    kept.put(nameKey, reply.get(nameKey));
    Object name = reply.get("name") != null ? reply.get("name") : reply.get("login");
    if (name != null) {
      kept.put("name", name);
    }
    return kept;
  }

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
