package ai.helper2.identity;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.apache.catalina.connector.Connector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

/** The second connector and the filter that keeps the back office off the public port (ADR 0035). */
@Configuration(proxyBeanMethods = false)
class AdminPortConfiguration {

    @Bean
    AdminPort adminPort(@Value("${helper2.admin.port:0}") int port) {
        return new AdminPort(port);
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

    /** Before the session and security filters: the public port answers 404, as if there were no back office. */
    @Bean
    FilterRegistrationBean<BackOfficeOffPublicPort> backOfficeOffPublicPort(AdminPort adminPort) {
        FilterRegistrationBean<BackOfficeOffPublicPort> registration = new FilterRegistrationBean<>(new BackOfficeOffPublicPort(adminPort));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }

    static final class BackOfficeOffPublicPort extends OncePerRequestFilter {
        private final AdminPort adminPort;

        BackOfficeOffPublicPort(AdminPort adminPort) {
            this.adminPort = adminPort;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
            if (!adminPort.admits(request) && AdminPort.isBackOffice(request)) {
                response.sendError(HttpServletResponse.SC_NOT_FOUND);
                return;
            }
            chain.doFilter(request, response);
        }
    }
}
