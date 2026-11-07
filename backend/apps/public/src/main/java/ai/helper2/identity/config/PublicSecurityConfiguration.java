package ai.helper2.identity.config;

import ai.helper2.identity.config.WebSecurity.CsrfCookieFilter;
import ai.helper2.identity.config.WebSecurity.HeaderOnlyCsrfTokenHandler;
import ai.helper2.monetisation.Monetisation;
import ai.helper2.platform.AdminHost;
import ai.helper2.platform.ProblemType;
import ai.helper2.platform.Problems;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.DefaultLoginPageConfigurer;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The public site's security (ADR 0025, 0032). Anonymous use is the default: search, details,
 * sources and reports need no account. Sign-in (OAuth2/OIDC, any configured provider) adds
 * server-side bookmarks and lets the user delete the account. The public app has no back office: it
 * never grants ADMIN, and {@code /admin} and {@code /api/v1/admin} answer 404 here, as any unknown
 * path.
 */
@Configuration(proxyBeanMethods = false)
class PublicSecurityConfiguration {

  /**
   * Strict CSP that still lets the same-origin SPA run (no inline scripts, no third-party origins).
   * The public site adds the origins of the monetisation parts that are on ({@link
   * ContentSecurityPolicy}, ADR 0030).
   */
  static final String CSP =
      "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; "
          + "connect-src 'self'; font-src 'self'; object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'";

  /** Where a sign-in returns: the start page of the site (H2_FRONTEND_URL). */
  @Bean
  SignInPages signInPages(IdentityProperties identity) {
    String url = identity.frontendUrl();
    return SignInPages.at(url.endsWith("/") ? url : url + "/");
  }

  /** No request is on the admin host here: the rate limits key on the client address. */
  @Bean
  AdminHost adminHost() {
    return AdminHost.NONE;
  }

  @Bean
  SecurityFilterChain api(
      HttpSecurity http,
      ObjectProvider<ClientRegistrationRepository> registrations,
      IdentityProperties identity,
      SignInPages pages,
      Monetisation monetisation)
      throws Exception {
    String csp = ContentSecurityPolicy.forPublicSite(monetisation);
    Set<String> admins = Set.copyOf(identity.admins());
    boolean metricsPublic = identity.metricsPublic();
    http.authorizeHttpRequests(
            a -> {
              a.requestMatchers("/actuator/health/**").permitAll();
              if (metricsPublic) {
                a.requestMatchers("/actuator/prometheus").permitAll();
              }
              // Nobody is ADMIN here: the rest of the actuator answers only on the management port.
              a.requestMatchers("/actuator/**")
                  .denyAll()
                  .requestMatchers("/api/v1/me/bookmarks", "/api/v1/me/bookmarks/**")
                  .authenticated()
                  .requestMatchers(HttpMethod.DELETE, "/api/v1/me")
                  .authenticated()
                  .requestMatchers(HttpMethod.PATCH, "/api/v1/me/preferences")
                  .authenticated()
                  .requestMatchers(
                      HttpMethod.GET,
                      "/api/v1/search",
                      "/api/v1/recent",
                      "/api/v1/events/upcoming",
                      "/api/v1/resources/**",
                      "/api/v1/sources",
                      "/api/v1/categories",
                      "/api/v1/home/examples",
                      "/api/v1/policy",
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
                .csrfTokenRequestHandler(new HeaderOnlyCsrfTokenHandler()))
        .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)
        .logout(l ->
            l.logoutUrl("/logout")
                .logoutSuccessHandler(
                    new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT)))
        .headers(h -> {
          h.addHeaderWriter(new CspHeaderWriter(csp))
              .permissionsPolicyHeader(p -> p.policy(WebSecurity.PERMISSIONS_POLICY))
              .referrerPolicy(r -> r.policy(ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN));
          if (identity.noindex()) {
            h.addHeaderWriter(WebSecurity.NOINDEX);
          }
        });
    WebSecurity.apiProblems(http);
    if (registrations.getIfAvailable() != null) {
      // End users sign in here, with this host's own callback ({baseUrl}). Never ADMIN: that role
      // exists only on the back office (ADR 0024, 0032).
      http.oauth2Login(o ->
          o.successHandler((req, res, auth) -> res.sendRedirect(pages.signedIn()))
              .failureHandler((req, res, e) -> res.sendRedirect(pages.failed()))
              .userInfoEndpoint(u ->
                  u.userService(WebSecurity.oauthUsers(admins, false))
                      .oidcUserService(WebSecurity.oidcUsers(admins, false))));
    }
    // No generated /login or /logout page: the SPA has its own sign-in and signs out with a POST,
    // and the generated pages would write the CSRF token into HTML (ADR 0024 (part was 0035) §8).
    http.removeConfigurer(DefaultLoginPageConfigurer.class);
    return http.build();
  }

  /**
   * Before the session and security filters: the back office paths answer 404 here, as if there
   * were no back office (it is another app, on another host).
   */
  @Bean
  FilterRegistrationBean<NoBackOfficeHere> noBackOfficeHere() {
    FilterRegistrationBean<NoBackOfficeHere> registration =
        new FilterRegistrationBean<>(new NoBackOfficeHere());
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
    return registration;
  }

  static final class NoBackOfficeHere extends OncePerRequestFilter {
    /**
     * The same problem body as any unknown path, without the path: it runs before the security
     * filters.
     */
    static final String NOT_FOUND = Problems.json(ProblemType.NOT_FOUND, null);

    @Override
    protected void doFilterInternal(
        HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
      if (isBackOffice(request)) {
        notFound(response);
        return;
      }
      chain.doFilter(request, response);
    }

    /**
     * The back office: the admin page and its API. Checked on the decoded, normalised path the
     * servlet container maps (servlet path + path info), so "%61dmin" or "x/../admin" cannot pass
     * as another path.
     */
    static boolean isBackOffice(HttpServletRequest request) {
      String path =
          request.getServletPath() + Optional.ofNullable(request.getPathInfo()).orElse("");
      return under(path, "/admin") || under(path, "/api/v1/admin");
    }

    private static boolean under(String path, String root) {
      return path.equals(root) || path.startsWith(root + "/");
    }

    /**
     * A plain 404 with the security headers the filter chain would add: this filter answers before
     * it runs, and the container's error page would echo the path and a timestamp.
     */
    static void notFound(HttpServletResponse response) throws IOException {
      response.setStatus(HttpServletResponse.SC_NOT_FOUND);
      response.setContentType("application/problem+json");
      response.setHeader("Cache-Control", "no-store");
      response.setHeader("X-Content-Type-Options", "nosniff");
      response.setHeader("X-Frame-Options", "DENY");
      response.setHeader("Content-Security-Policy", CSP);
      response.setHeader("Permissions-Policy", WebSecurity.PERMISSIONS_POLICY);
      response.getWriter().write(NOT_FOUND);
    }
  }
}
