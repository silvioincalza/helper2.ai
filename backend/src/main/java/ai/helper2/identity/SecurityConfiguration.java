package ai.helper2.identity;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
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
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Anonymous use is the default: search, details, sources and reports need no account.
 * Sign-in (OAuth2/OIDC, any configured provider) adds server-side bookmarks and lets the user delete the account;
 * ADMIN comes from an explicit allow-list: "registrationId:subject" (GitHub: numeric id, never the login), or
 * "registrationId:email" for OIDC providers when the email is verified.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {

    /** Strict CSP that still lets the same-origin SPA run (no inline scripts, no third-party origins). */
    static final String CSP = "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; "
        + "connect-src 'self'; font-src 'self'; object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'";

    @Bean
    SecurityFilterChain api(HttpSecurity http, ObjectProvider<ClientRegistrationRepository> registrations,
                            @Value("${helper2.security.admins:}") String admins,
                            @Value("${helper2.security.metrics-public:false}") boolean metricsPublic,
                            @Value("${helper2.frontend-url:/}") String frontendUrl) throws Exception {
        Set<String> adminSet = Arrays.stream(admins.split(",")).map(String::trim).filter(s -> !s.isEmpty())
            .collect(Collectors.toUnmodifiableSet());
        http
            .authorizeHttpRequests(a -> {
                a.requestMatchers("/actuator/health/**", "/actuator/info").permitAll();
                if (metricsPublic) {
                    a.requestMatchers("/actuator/prometheus").permitAll();
                }
                a.requestMatchers("/actuator/**").hasRole("ADMIN")
                    .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                    .requestMatchers("/api/v1/me/bookmarks", "/api/v1/me/bookmarks/**").authenticated()
                    .requestMatchers(HttpMethod.DELETE, "/api/v1/me").authenticated()
                    .requestMatchers(HttpMethod.GET, "/api/v1/search", "/api/v1/recent", "/api/v1/events/upcoming", "/api/v1/resources/**", "/api/v1/sources", "/api/v1/me").permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/v1/resources/*/reports", "/api/v1/resources/*/guide", "/api/v1/guide", "/api/v1/guide/stream").permitAll()
                    .requestMatchers("/api/**").denyAll()
                    .anyRequest().permitAll();
            })
            // Sessions (in Redis, shared by the replicas) exist only for sign-in: an anonymous request never creates
            // one. A refused anonymous request would otherwise be saved in a new session for after sign-in, which
            // always returns to the admin page anyway (defaultSuccessUrl below).
            .requestCache(r -> r.requestCache(new NullRequestCache()))
            .csrf(c -> c.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse()).spa())
            .addFilterAfter(new CsrfCookieFilter(), org.springframework.security.web.csrf.CsrfFilter.class)
            .exceptionHandling(e -> e.defaultAuthenticationEntryPointFor(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                PathPatternRequestMatcher.withDefaults().matcher("/api/**")))
            .logout(l -> l.logoutUrl("/logout").logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT)))
            .headers(h -> h
                .contentSecurityPolicy(csp -> csp.policyDirectives(CSP))
                .referrerPolicy(r -> r.policy(org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN)));
        if (registrations.getIfAvailable() != null) {
            http.oauth2Login(o -> o
                .defaultSuccessUrl(adminPage(frontendUrl), true)
                .failureUrl(adminPage(frontendUrl) + "?signin=failed")
                .userInfoEndpoint(u -> u.userService(oauthUsers(adminSet)).oidcUserService(oidcUsers(adminSet))));
        }
        return http.build();
    }

    /** Sign-in is offered on the admin page only, so OAuth2 returns there ("/" → "/admin"). */
    static String adminPage(String frontendUrl) {
        return (frontendUrl.endsWith("/") ? frontendUrl : frontendUrl + "/") + "admin";
    }

    static OAuth2UserService<OAuth2UserRequest, OAuth2User> oauthUsers(Set<String> admins) {
        DefaultOAuth2UserService delegate = new DefaultOAuth2UserService();
        return req -> {
            OAuth2User user = delegate.loadUser(req);
            String reg = req.getClientRegistration().getRegistrationId();
            String key = req.getClientRegistration().getProviderDetails().getUserInfoEndpoint().getUserNameAttributeName();
            // Match only the immutable subject (GitHub: numeric id). Logins can be renamed and re-registered.
            Set<GrantedAuthority> roles = roles(admins, reg, user.getName(), null);
            return new DefaultOAuth2User(roles, user.getAttributes(), key == null || key.isBlank() ? "id" : key);
        };
    }

    static OAuth2UserService<OidcUserRequest, OidcUser> oidcUsers(Set<String> admins) {
        OidcUserService delegate = new OidcUserService();
        return req -> {
            OidcUser user = delegate.loadUser(req);
            String reg = req.getClientRegistration().getRegistrationId();
            Boolean verified = user.getEmailVerified();
            String email = Boolean.TRUE.equals(verified) ? user.getEmail() : null;
            return new DefaultOidcUser(roles(admins, reg, user.getSubject(), email), user.getIdToken(), user.getUserInfo());
        };
    }

    static Set<GrantedAuthority> roles(Set<String> admins, String registrationId, String subject, String alias) {
        Set<GrantedAuthority> roles = new HashSet<>();
        roles.add(new SimpleGrantedAuthority("ROLE_USER"));
        if (admins.contains(registrationId + ":" + subject) || (alias != null && admins.contains(registrationId + ":" + alias))) {
            roles.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        }
        return roles;
    }

    /** Forces the deferred CSRF token to load so the XSRF-TOKEN cookie exists before the SPA's first POST. */
    static final class CsrfCookieFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(jakarta.servlet.http.HttpServletRequest req, jakarta.servlet.http.HttpServletResponse res,
                                        jakarta.servlet.FilterChain chain) throws jakarta.servlet.ServletException, java.io.IOException {
            Object token = req.getAttribute(CsrfToken.class.getName());
            if (token instanceof CsrfToken t) {
                t.getToken();
            }
            chain.doFilter(req, res);
        }
    }

}
