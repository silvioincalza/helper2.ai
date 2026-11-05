package ai.helper2.identity.config;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Anonymous use is the default: search, details, sources and reports need no account. Sign-in
 * (OAuth2/OIDC, any configured provider) adds server-side bookmarks and lets the user delete the
 * account; ADMIN comes from an explicit allow-list: "registrationId:subject" (GitHub: numeric id,
 * never the login), or "registrationId:email" for OIDC providers when the email is verified.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {

  /**
   * Strict CSP that still lets the same-origin SPA run (no inline scripts, no third-party origins).
   */
  static final String CSP =
      "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; "
          + "connect-src 'self'; font-src 'self'; object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'";

  /**
   * The site uses no camera, microphone, location, payment or USB: no page, and no script on it,
   * may ask.
   */
  static final String PERMISSIONS_POLICY =
      "camera=(), microphone=(), geolocation=(), payment=(), usb=()";

  /**
   * On a deployment that must stay out of search engines (staging), every response says so. A
   * header and not a robots.txt Disallow: a crawler must fetch the page to see the noindex.
   */
  static final StaticHeadersWriter NOINDEX =
      new StaticHeadersWriter("X-Robots-Tag", "noindex, nofollow");

  @Bean
  SecurityFilterChain api(
      HttpSecurity http,
      ObjectProvider<ClientRegistrationRepository> registrations,
      @Value("${helper2.security.admins:}") String admins,
      @Value("${helper2.security.metrics-public:false}") boolean metricsPublic,
      @Value("${helper2.frontend-url:/}") String frontendUrl,
      @Value("${helper2.security.noindex:false}") boolean noindex,
      AdminPort adminPort)
      throws Exception {
    Set<String> adminSet =
        Arrays.stream(admins.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .collect(Collectors.toUnmodifiableSet());
    http.authorizeHttpRequests(
            a -> {
              a.requestMatchers("/actuator/health/**").permitAll();
              if (metricsPublic) {
                a.requestMatchers("/actuator/prometheus").permitAll();
              }
              a.requestMatchers("/actuator/**")
                  .hasRole("ADMIN")
                  .requestMatchers("/api/v1/admin/**")
                  .hasRole("ADMIN")
                  .requestMatchers("/api/v1/me/bookmarks", "/api/v1/me/bookmarks/**")
                  .authenticated()
                  .requestMatchers(HttpMethod.DELETE, "/api/v1/me")
                  .authenticated()
                  .requestMatchers(
                      HttpMethod.GET,
                      "/api/v1/search",
                      "/api/v1/recent",
                      "/api/v1/events/upcoming",
                      "/api/v1/resources/**",
                      "/api/v1/sources",
                      "/api/v1/me")
                  .permitAll()
                  .requestMatchers(
                      HttpMethod.POST,
                      "/api/v1/resources/*/reports",
                      "/api/v1/resources/*/guide",
                      "/api/v1/guide/stream")
                  .permitAll()
                  .requestMatchers("/api/**")
                  .denyAll()
                  .anyRequest()
                  .permitAll();
            })
        // Sessions (in Redis, shared by the replicas) exist only for sign-in: an anonymous request
        // never creates one. A refused anonymous request would otherwise be saved in a new session
        // for after sign-in, which returns to a fixed page anyway (the success handler below).
        .requestCache(r -> r.requestCache(new NullRequestCache()))
        // The SPA setup of .spa(), but deferred: .spa() loads the token on every request, so every
        // response without the cookie would set it. CsrfCookieFilter loads it on GET /api/v1/me.
        .csrf(c ->
            c.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(new DeferredSpaCsrfTokenRequestHandler()))
        .addFilterAfter(
            new CsrfCookieFilter(), org.springframework.security.web.csrf.CsrfFilter.class)
        .exceptionHandling(e ->
            e.defaultAuthenticationEntryPointFor(
                new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                PathPatternRequestMatcher.withDefaults().matcher("/api/**")))
        .logout(l ->
            l.logoutUrl("/logout")
                .logoutSuccessHandler(
                    new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT)))
        .headers(h -> {
          h.contentSecurityPolicy(csp -> csp.policyDirectives(CSP))
              .permissionsPolicyHeader(p -> p.policy(PERMISSIONS_POLICY))
              .referrerPolicy(r ->
                  r.policy(
                      org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter
                          .ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN));
          if (noindex) {
            h.addHeaderWriter(NOINDEX);
          }
        });
    if (registrations.getIfAvailable() != null) {
      // Sign-in on both ports, each host with its own callback ({baseUrl}): end users on the public
      // host, the owner on the admin host. ADMIN only for a sign-in on the admin port (ADR 0035).
      BooleanSupplier onAdminPort = adminPort::currentRequestAdmitted;
      http.oauth2Login(o ->
          o.successHandler((req, res, auth) ->
                  res.sendRedirect(afterSignIn(frontendUrl, adminPort.admits(req))))
              .failureHandler((req, res, e) ->
                  res.sendRedirect(
                      afterSignIn(frontendUrl, adminPort.admits(req)) + "?signin=failed"))
              .userInfoEndpoint(u ->
                  u.userService(oauthUsers(adminSet, onAdminPort))
                      .oidcUserService(oidcUsers(adminSet, onAdminPort))));
    }
    return http.build();
  }

  /** The admin page under the frontend URL ("/" → "/admin"). */
  static String adminPage(String frontendUrl) {
    return (frontendUrl.endsWith("/") ? frontendUrl : frontendUrl + "/") + "admin";
  }

  /** Where a sign-in returns: the back office on the admin port, the site on the public port. */
  static String afterSignIn(String frontendUrl, boolean onAdminPort) {
    return onAdminPort
        ? adminPage(frontendUrl)
        : (frontendUrl.endsWith("/") ? frontendUrl : frontendUrl + "/");
  }

  static OAuth2UserService<OAuth2UserRequest, OAuth2User> oauthUsers(
      Set<String> admins, BooleanSupplier onAdminPort) {
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
      Set<GrantedAuthority> roles =
          roles(admins, reg, user.getName(), null, onAdminPort.getAsBoolean());
      return new DefaultOAuth2User(
          roles, user.getAttributes(), key == null || key.isBlank() ? "id" : key);
    };
  }

  static OAuth2UserService<OidcUserRequest, OidcUser> oidcUsers(
      Set<String> admins, BooleanSupplier onAdminPort) {
    OidcUserService delegate = new OidcUserService();
    return req -> {
      OidcUser user = delegate.loadUser(req);
      String reg = req.getClientRegistration().getRegistrationId();
      Boolean verified = user.getEmailVerified();
      String email = Boolean.TRUE.equals(verified) ? user.getEmail() : null;
      return new DefaultOidcUser(
          roles(admins, reg, user.getSubject(), email, onAdminPort.getAsBoolean()),
          user.getIdToken(),
          user.getUserInfo());
    };
  }

  static Set<GrantedAuthority> roles(
      Set<String> admins,
      String registrationId,
      String subject,
      String alias,
      boolean onAdminPort) {
    Set<GrantedAuthority> roles = new HashSet<>();
    roles.add(new SimpleGrantedAuthority("ROLE_USER"));
    boolean allowListed =
        admins.contains(registrationId + ":" + subject)
            || (alias != null && admins.contains(registrationId + ":" + alias));
    if (onAdminPort && allowListed) {
      roles.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }
    return roles;
  }

  /**
   * Checks a token as Spring's SPA handler does: the raw cookie value when it comes in the {@code
   * X-XSRF-TOKEN} header (what the SPA sends), the masked value when it comes as a form parameter.
   * Unlike that handler it never loads the token itself, so a response only gets the cookie when
   * something asks for the token.
   */
  static final class DeferredSpaCsrfTokenRequestHandler implements CsrfTokenRequestHandler {
    private final CsrfTokenRequestHandler plain = new CsrfTokenRequestAttributeHandler();
    private final CsrfTokenRequestHandler xor = new XorCsrfTokenRequestAttributeHandler();

    @Override
    public void handle(
        jakarta.servlet.http.HttpServletRequest request,
        jakarta.servlet.http.HttpServletResponse response,
        Supplier<CsrfToken> csrfToken) {
      xor.handle(request, response, csrfToken);
    }

    @Override
    public String resolveCsrfTokenValue(
        jakarta.servlet.http.HttpServletRequest request, CsrfToken csrfToken) {
      String header = request.getHeader(csrfToken.getHeaderName());
      return (StringUtils.hasText(header) ? plain : xor).resolveCsrfTokenValue(request, csrfToken);
    }
  }

  /**
   * Forces the deferred CSRF token to load on {@code GET /api/v1/me}, which the SPA calls when it
   * starts, so the XSRF-TOKEN cookie exists before its first POST. Only there: on every other
   * response (static files, the public cacheable GETs) a Set-Cookie would stop a CDN from caching
   * it, and could share one token between visitors. A POST that comes without the cookie is refused
   * (403) and gets a fresh cookie with the refusal.
   */
  static final class CsrfCookieFilter extends OncePerRequestFilter {
    static final String WRITES_COOKIE = "/api/v1/me";

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
