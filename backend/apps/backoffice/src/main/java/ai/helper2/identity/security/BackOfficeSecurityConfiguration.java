package ai.helper2.identity.security;

import ai.helper2.identity.CurrentUser;
import ai.helper2.identity.config.IdentityProperties;
import ai.helper2.identity.config.KnownAccountOnly;
import ai.helper2.identity.config.SameOriginWrites;
import ai.helper2.identity.config.SignInPages;
import ai.helper2.identity.config.WebSecurity;
import ai.helper2.identity.config.WebSecurity.CsrfCookieFilter;
import ai.helper2.identity.config.WebSecurity.HeaderOnlyCsrfTokenHandler;
import ai.helper2.platform.RateLimitKey;
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
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

@Configuration(proxyBeanMethods = false)
class BackOfficeSecurityConfiguration {
  static final String CSRF_COOKIE = "__Host-XSRF-TOKEN";

  static final RequestMatcher SIGNED_IN_PATHS =
      new OrRequestMatcher(
          WebSecurity.API, PathPatternRequestMatcher.withDefaults().matcher("/actuator/**"));

  @Bean
  SignInPages signInPages(IdentityProperties identity) {
    return SignInPages.at(identity.frontendUrl());
  }

  @Bean
  RateLimitKey rateLimitKey() {
    return RateLimitKey.SIGNED_IN_ADMIN;
  }

  @Bean
  SecurityFilterChain api(
      HttpSecurity http,
      ObjectProvider<ClientRegistrationRepository> registrations,
      IdentityProperties identity,
      SignInPages pages,
      OAuth2AuthorizedClientRepository signInStore,
      CurrentUser currentUser)
      throws Exception {
    Set<String> admins = Set.copyOf(identity.admins());
    boolean metricsPublic = identity.metricsPublic();
    CookieCsrfTokenRepository csrfTokens = csrfCookies();
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
            c.csrfTokenRepository(csrfTokens)
                .csrfTokenRequestHandler(new HeaderOnlyCsrfTokenHandler()))
        .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)
        .addFilterBefore(new SameOriginWrites(), CsrfFilter.class)
        .addFilterBefore(
            new KnownAccountOnly(currentUser, SIGNED_IN_PATHS, csrfTokens),
            AuthorizationFilter.class)
        .logout(l ->
            l.logoutUrl("/logout")
                .logoutSuccessHandler(
                    new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT)))
        .headers(h -> h.disable());
    WebSecurity.apiProblems(http);
    if (registrations.getIfAvailable() != null) {
      http.oauth2Login(o ->
          o.authorizedClientRepository(signInStore)
              .successHandler((req, res, auth) -> res.sendRedirect(pages.signedIn()))
              .failureHandler((req, res, e) -> res.sendRedirect(pages.failed()))
              .userInfoEndpoint(u ->
                  u.userService(WebSecurity.oauthUsers(admins, true))
                      .oidcUserService(WebSecurity.oidcUsers(admins, true))));
    }
    http.removeConfigurer(DefaultLoginPageConfigurer.class);
    return http.build();
  }

  static CookieCsrfTokenRepository csrfCookies() {
    CookieCsrfTokenRepository cookies = CookieCsrfTokenRepository.withHttpOnlyFalse();
    cookies.setCookieName(CSRF_COOKIE);
    cookies.setCookieCustomizer(c -> c.secure(true).path("/").sameSite("Strict"));
    return cookies;
  }

  @Bean
  FilterRegistrationBean<BackOfficeEdge> backOfficeEdge() {
    FilterRegistrationBean<BackOfficeEdge> registration =
        new FilterRegistrationBean<>(new BackOfficeEdge());
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
    return registration;
  }
}
