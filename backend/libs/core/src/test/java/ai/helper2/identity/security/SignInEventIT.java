package ai.helper2.identity.security;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.TestcontainersConfiguration;
import ai.helper2.identity.Users;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.authentication.event.InteractiveAuthenticationSuccessEvent;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.web.OAuth2LoginAuthenticationFilter;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SignInEventIT {

  static final Instant LONG_AGO = Instant.parse("2000-07-01T00:00:00Z");

  @Autowired ApplicationEventPublisher events;

  @Autowired JdbcClient jdbc;

  @Autowired Users users;

  final String subject = "sign-in-" + UUID.randomUUID();

  OAuth2AuthenticationToken sessionToken(String displayName) {
    List<SimpleGrantedAuthority> roles = List.of(new SimpleGrantedAuthority("ROLE_USER"));
    return new OAuth2AuthenticationToken(
        new DefaultOAuth2User(roles, Map.of("sub", subject, "name", displayName), "sub"),
        roles,
        "github");
  }

  void signIn(String displayName) {
    events.publishEvent(
        new InteractiveAuthenticationSuccessEvent(
            sessionToken(displayName), OAuth2LoginAuthenticationFilter.class));
  }

  Instant lastSignedIn(UUID id) {
    return jdbc.sql("SELECT last_signed_in_at FROM app_user WHERE id = :id")
        .param("id", id)
        .query(Timestamp.class)
        .single()
        .toInstant();
  }

  @Test
  void aSignInWritesTheAccountAndEveryLaterOneRefreshesTheLastSignIn() {
    signIn("Someone");
    UUID id = users.find("github", subject).orElseThrow();
    jdbc.sql("UPDATE app_user SET last_signed_in_at = :at WHERE id = :id")
        .param("at", Timestamp.from(LONG_AGO))
        .param("id", id)
        .update();

    signIn("Someone Renamed");

    assertThat(users.find("github", subject)).contains(id);
    assertThat(lastSignedIn(id)).isAfter(LONG_AGO);
    assertThat(
            jdbc.sql("SELECT display_name FROM app_user WHERE id = :id")
                .param("id", id)
                .query(String.class)
                .single())
        .isEqualTo("Someone Renamed");
  }

  @Test
  void theProviderManagersEventAndOtherSignInsWriteNothing() {
    events.publishEvent(new AuthenticationSuccessEvent(sessionToken("Someone")));
    events.publishEvent(
        new InteractiveAuthenticationSuccessEvent(
            UsernamePasswordAuthenticationToken.authenticated(subject, null, List.of()),
            OAuth2LoginAuthenticationFilter.class));

    assertThat(users.find("github", subject)).isEmpty();
  }
}
