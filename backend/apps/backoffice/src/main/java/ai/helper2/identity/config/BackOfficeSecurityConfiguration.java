package ai.helper2.identity.config;

import ai.helper2.identity.config.WebSecurity.CsrfCookieFilter;
import ai.helper2.identity.config.WebSecurity.HeaderOnlyCsrfTokenHandler;
import ai.helper2.platform.AdminHost;
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
import org.springframework.security.web.savedrequest.NullRequestCache;

/**
 * The back office's security (ADR 0024, 0032). The whole app is the admin host: every request is
 * the owner's, so every write must come from the admin host's own pages (Origin, then the CSRF
 * header), the cookies are {@code __Host-} cookies that a sibling host cannot set, and a sign-in of
 * an allow-listed account carries ADMIN. The headers and the cache rules are {@link
 * BackOfficeEdge}'s, on every response.
 */
@Configuration(proxyBeanMethods = false)
class BackOfficeSecurityConfiguration {

  /** The CSRF cookie: {@code __Host-} so only this host sets it (Secure, Path=/, no Domain). */
  static final String CSRF_COOKIE = "__Host-XSRF-TOKEN";

  /** Where a sign-in returns: the back office (helper2.identity.frontend-url, {@code /admin}). */
  @Bean
  SignInPages signInPages(IdentityProperties identity) {
    return SignInPages.at(identity.frontendUrl());
  }

  /** Every request here is on the admin host (rate limits key on the signed-in administrator). */
  @Bean
  AdminHost adminHost() {
    return () -> true;
  }

  @Bean
  SecurityFilterChain api(
      HttpSecurity http,
      ObjectProvider<ClientRegistrationRepository> registrations,
      IdentityProperties identity,
      SignInPages pages)
      throws Exception {
    Set<String> admins = Set.copyOf(identity.admins());
    boolean metricsPublic = identity.metricsPublic();
    http.authorizeHttpRequests(
            a -> {
              a.requestMatchers("/actuator/health/**").permitAll();
              if (metricsPublic) {
                a.requestMatchers("/actuator/prometheus").permitAll();
              }
              a.requestMatchers("/actuator/**")
                  .hasRole("ADMIN")
                  .requestMatchers(HttpMethod.GET, "/api/v1/me")
                  .permitAll()
                  .requestMatchers("/api/v1/admin/**")
                  .hasRole("ADMIN")
                  // The reads the admin pages make (review, examples, relationship target).
                  .requestMatchers(
                      HttpMethod.GET, "/api/v1/search", "/api/v1/sources", "/api/v1/categories")
                  .hasRole("ADMIN")
                  .requestMatchers("/api/**")
                  .denyAll()
                  .anyRequest()
                  .permitAll();
            })
        .requestCache(r -> r.requestCache(new NullRequestCache()))
        .csrf(c ->
            c.csrfTokenRepository(csrfCookies())
                .csrfTokenRequestHandler(new HeaderOnlyCsrfTokenHandler()))
        .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)
        // Every write must carry this host's own Origin, before the CSRF check (ADR 0024 (part was
        // 0035) §8).
        .addFilterBefore(new SameOriginWrites(), CsrfFilter.class)
        .logout(l ->
            l.logoutUrl("/logout")
                .logoutSuccessHandler(
                    new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT)))
        // BackOfficeEdge writes the headers of every response, errors and 404s included.
        .headers(h -> h.disable());
    WebSecurity.apiProblems(http);
    if (registrations.getIfAvailable() != null) {
      // The owner signs in here. The callback is pinned (H2_ADMIN_BASE_URL) and the sign-in returns
      // to the relative /admin, never to an address built from a forwarded host.
      http.oauth2Login(o ->
          o.successHandler((req, res, auth) -> res.sendRedirect(pages.signedIn()))
              .failureHandler((req, res, e) -> res.sendRedirect(pages.failed()))
              .userInfoEndpoint(u ->
                  u.userService(WebSecurity.oauthUsers(admins, true))
                      .oidcUserService(WebSecurity.oidcUsers(admins, true))));
    }
    http.removeConfigurer(DefaultLoginPageConfigurer.class);
    return http.build();
  }

  /** {@code __Host-XSRF-TOKEN}: Secure, Path=/, SameSite=Strict, readable by the admin web app. */
  static CookieCsrfTokenRepository csrfCookies() {
    CookieCsrfTokenRepository cookies = CookieCsrfTokenRepository.withHttpOnlyFalse();
    cookies.setCookieName(CSRF_COOKIE);
    cookies.setCookieCustomizer(c -> c.secure(true).path("/").sameSite("Strict"));
    return cookies;
  }

  /** Before the session and security filters: only the back office's own paths answer. */
  @Bean
  FilterRegistrationBean<BackOfficeEdge> backOfficeEdge() {
    FilterRegistrationBean<BackOfficeEdge> registration =
        new FilterRegistrationBean<>(new BackOfficeEdge());
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
    return registration;
  }
}
