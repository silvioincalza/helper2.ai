package ai.helper2.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.security.authentication.event.InteractiveAuthenticationSuccessEvent;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.web.filter.OncePerRequestFilter;

@TestConfiguration(proxyBeanMethods = false)
public class SessionsComeFromASignIn {

  @Bean
  Customizer<HttpSecurity> everyMockSessionSignedIn(ApplicationEventPublisher events) {
    return http -> http.addFilterAfter(new SignIn(events), SecurityContextHolderFilter.class);
  }

  static final class SignIn extends OncePerRequestFilter {
    private final ApplicationEventPublisher events;

    SignIn(ApplicationEventPublisher events) {
      this.events = events;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
      Authentication session = SecurityContextHolder.getContext().getAuthentication();
      if (session instanceof OAuth2AuthenticationToken) {
        events.publishEvent(new InteractiveAuthenticationSuccessEvent(session, getClass()));
      }
      chain.doFilter(request, response);
    }
  }
}
