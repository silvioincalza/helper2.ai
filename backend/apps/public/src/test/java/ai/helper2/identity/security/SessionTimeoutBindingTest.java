package ai.helper2.identity.security;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.testing.ConfigBinding;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.session.autoconfigure.SessionProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.server.autoconfigure.ServerProperties;
import org.springframework.context.annotation.Configuration;

class SessionTimeoutBindingTest {

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties({ServerProperties.class, SessionProperties.class})
  static class Binding {}

  final ApplicationContextRunner context = ConfigBinding.of(Binding.class);

  @Test
  void aSignInEndsThirtyMinutesAfterTheLastRequestAsThePrivacyPageSays() {
    context.run(c -> {
      assertThat(c.getBean(SessionProperties.class).getTimeout())
          .as("spring.session.timeout would override the stated timeout")
          .isNull();
      assertThat(c.getBean(ServerProperties.class).getServlet().getSession().getTimeout())
          .as("change the Privacy page (Cookies: the session cookie) with it")
          .isEqualTo(Duration.ofMinutes(30));
    });
  }
}
