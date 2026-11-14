package ai.helper2.identity.security;

import ai.helper2.identity.CurrentUser;
import ai.helper2.identity.config.IdentityProperties;
import ai.helper2.identity.config.KnownAccountOnly;
import ai.helper2.identity.config.SignInPages;
import ai.helper2.identity.config.WebSecurity;
import ai.helper2.identity.config.WebSecurity.CsrfCookieFilter;
import ai.helper2.identity.config.WebSecurity.HeaderOnlyCsrfTokenHandler;
import ai.helper2.platform.ProblemType;
import ai.helper2.platform.Problems;
import ai.helper2.platform.RateLimitKey;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.DefaultLoginPageConfigurer;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.header.HeaderWriter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration(proxyBeanMethods = false)
class PublicSecurityConfiguration {
  static final RequestMatcher ACCOUNT_PATHS =
      PathPatternRequestMatcher.withDefaults().matcher("/api/v1/me/**");

  @Bean
  SignInPages signInPages(IdentityProperties identity) {
    String url = identity.frontendUrl();
    return SignInPages.at(url.endsWith("/") ? url : url + "/");
  }

  @Bean
  RateLimitKey rateLimitKey() {
    return RateLimitKey.CLIENT_ADDRESS;
  }

  @Bean
  SecurityFilterChain api(
      HttpSecurity http,
      ObjectProvider<ClientRegistrationRepository> registrations,
      IdentityProperties identity,
      SignInPages pages,
      OAuth2AuthorizedClientRepository signInStore,
      CurrentUser currentUser,
      @Qualifier("contentSecurityPolicy") HeaderWriter csp)
      throws Exception {
    Set<String> admins = Set.copyOf(identity.admins());
    boolean metricsPublic = identity.metricsPublic();
    CookieCsrfTokenRepository csrfTokens = CookieCsrfTokenRepository.withHttpOnlyFalse();
    http.authorizeHttpRequests(
            a -> {
              a.requestMatchers("/actuator/health/**").permitAll();
              if (metricsPublic) {
                a.requestMatchers("/actuator/prometheus").permitAll();
              }
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
                      "/api/v1/site",
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
        .requestCache(r -> r.requestCache(new NullRequestCache()))
        .csrf(c ->
            c.csrfTokenRepository(csrfTokens)
                .csrfTokenRequestHandler(new HeaderOnlyCsrfTokenHandler()))
        .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)
        .addFilterBefore(
            new KnownAccountOnly(currentUser, ACCOUNT_PATHS, csrfTokens), AuthorizationFilter.class)
        .logout(l ->
            l.logoutUrl("/logout")
                .logoutSuccessHandler(
                    new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT)))
        .headers(h -> {
          h.addHeaderWriter(csp)
              .permissionsPolicyHeader(p -> p.policy(WebSecurity.PERMISSIONS_POLICY))
              .referrerPolicy(r -> r.policy(ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN));
          if (identity.noindex()) {
            h.addHeaderWriter(WebSecurity.NOINDEX);
          }
        });
    WebSecurity.apiProblems(http);
    if (registrations.getIfAvailable() != null) {
      http.oauth2Login(o ->
          o.authorizedClientRepository(signInStore)
              .successHandler((req, res, auth) -> res.sendRedirect(pages.signedIn()))
              .failureHandler((req, res, e) -> res.sendRedirect(pages.failed()))
              .userInfoEndpoint(u ->
                  u.userService(WebSecurity.oauthUsers(admins, false))
                      .oidcUserService(WebSecurity.oidcUsers(admins, false))));
    }
    http.removeConfigurer(DefaultLoginPageConfigurer.class);
    return http.build();
  }

  @Bean
  FilterRegistrationBean<NoBackOfficeHere> noBackOfficeHere(
      @Qualifier("strictContentSecurityPolicy") HeaderWriter strictCsp) {
    FilterRegistrationBean<NoBackOfficeHere> registration =
        new FilterRegistrationBean<>(new NoBackOfficeHere(strictCsp));
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
    return registration;
  }

  static final class NoBackOfficeHere extends OncePerRequestFilter {
    static final String NOT_FOUND = Problems.json(ProblemType.NOT_FOUND, null);

    private final HeaderWriter strictCsp;

    NoBackOfficeHere(HeaderWriter strictCsp) {
      this.strictCsp = strictCsp;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
      if (isBackOffice(request)) {
        notFound(request, response);
        return;
      }
      chain.doFilter(request, response);
    }

    static boolean isBackOffice(HttpServletRequest request) {
      String path =
          request.getServletPath() + Optional.ofNullable(request.getPathInfo()).orElse("");
      return under(path, "/admin") || under(path, "/api/v1/admin");
    }

    private static boolean under(String path, String root) {
      return path.equals(root) || path.startsWith(root + "/");
    }

    private void notFound(HttpServletRequest request, HttpServletResponse response)
        throws IOException {
      response.setStatus(HttpServletResponse.SC_NOT_FOUND);
      response.setContentType("application/problem+json");
      response.setHeader("Cache-Control", "no-store");
      response.setHeader("X-Content-Type-Options", "nosniff");
      response.setHeader("X-Frame-Options", "DENY");
      strictCsp.writeHeaders(request, response);
      response.setHeader("Permissions-Policy", WebSecurity.PERMISSIONS_POLICY);
      response.getWriter().write(NOT_FOUND);
    }
  }
}
