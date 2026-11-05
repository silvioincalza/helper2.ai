package ai.helper2.identity.config;

import ai.helper2.platform.ProblemType;
import ai.helper2.platform.Problems;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.apache.catalina.connector.Connector;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.ResolvableType;
import org.springframework.core.env.Environment;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The second connector and the filter that keeps the back office off the public port (ADR 0035).
 */
@Configuration(proxyBeanMethods = false)
class AdminPortConfiguration {

  @Bean
  AdminPort adminPort(IdentityProperties identity, Environment environment) {
    AdminPort adminPort = new AdminPort(identity.adminPort());
    requireAdminPortWithRealSignIn(adminPort, environment);
    return adminPort;
  }

  /**
   * Fail closed (ADR 0035): with a real sign-in provider and no admin port, the back office and
   * ADMIN would be back on the public host. That is allowed only on a developer's machine (profile
   * dev). A provider is any OAuth2 client registration, however it is set (the oauth-github
   * profile, environment variables, a future provider).
   */
  static void requireAdminPortWithRealSignIn(AdminPort adminPort, Environment environment) {
    if (!adminPort.enabled()
        && hasSignInProvider(environment)
        && !environment.matchesProfiles("dev")) {
      throw new IllegalStateException(
          "H2_ADMIN_PORT is not set while sign-in is on: the back office would answer on the public host. "
              + "Set H2_ADMIN_PORT (ADR 0035), or use the dev profile on a developer machine.");
    }
  }

  private static final Bindable<Map<String, Map<String, String>>> REGISTRATIONS =
      Bindable.of(
          ResolvableType.forClassWithGenerics(
              Map.class,
              ResolvableType.forClass(String.class),
              ResolvableType.forClassWithGenerics(Map.class, String.class, String.class)));

  /** A registration that cannot be read (a missing secret) still counts: the check fails closed. */
  static boolean hasSignInProvider(Environment environment) {
    try {
      return Binder.get(environment)
          .bind("spring.security.oauth2.client.registration", REGISTRATIONS)
          .map(registrations -> !registrations.isEmpty())
          .orElse(false);
    } catch (RuntimeException unreadable) {
      return true;
    }
  }

  @Bean
  WebServerFactoryCustomizer<TomcatServletWebServerFactory> adminConnector(AdminPort adminPort) {
    return factory -> {
      if (adminPort.enabled()) {
        Connector connector = new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
        connector.setPort(adminPort.port());
        factory.addAdditionalConnectors(connector);
      }
    };
  }

  /**
   * Before the session and security filters: the public port answers 404, as if there were no back
   * office.
   */
  @Bean
  FilterRegistrationBean<BackOfficeOffPublicPort> backOfficeOffPublicPort(AdminPort adminPort) {
    FilterRegistrationBean<BackOfficeOffPublicPort> registration =
        new FilterRegistrationBean<>(new BackOfficeOffPublicPort(adminPort));
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
    return registration;
  }

  static final class BackOfficeOffPublicPort extends OncePerRequestFilter {
    /**
     * The same problem body as any unknown path, without the path: it runs before the security
     * filters.
     */
    static final String NOT_FOUND = Problems.json(ProblemType.NOT_FOUND, null);

    private final AdminPort adminPort;

    BackOfficeOffPublicPort(AdminPort adminPort) {
      this.adminPort = adminPort;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
      if (!adminPort.admits(request) && AdminPort.isBackOffice(request)) {
        notFound(response);
        return;
      }
      chain.doFilter(request, response);
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
      response.setHeader("Content-Security-Policy", SecurityConfiguration.CSP);
      response.setHeader("Permissions-Policy", SecurityConfiguration.PERMISSIONS_POLICY);
      response.getWriter().write(NOT_FOUND);
    }
  }
}
