package ai.helper2.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.helper2.identity.SignInAgain;
import ai.helper2.identity.Users;
import ai.helper2.platform.ProblemType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.event.InteractiveAuthenticationSuccessEvent;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;

class SignedInUserTest {

  static final List<SimpleGrantedAuthority> USER = List.of(new SimpleGrantedAuthority("ROLE_USER"));

  static final class RecordingUsers implements Users {
    final Map<String, UUID> accounts = new HashMap<>();
    final List<String> signIns = new ArrayList<>();

    @Override
    public Optional<UUID> find(String provider, String subject) {
      return Optional.ofNullable(accounts.get(provider + ":" + subject));
    }

    @Override
    public UUID signedIn(String provider, String subject, String displayName) {
      signIns.add(provider + ":" + subject + ":" + displayName);
      return accounts.computeIfAbsent(provider + ":" + subject, key -> UUID.randomUUID());
    }

    @Override
    public Optional<Preferences> preferences(UUID userId) {
      return Optional.empty();
    }

    @Override
    public Optional<Preferences> changeRadarMotion(UUID userId, boolean radarMotion) {
      return Optional.empty();
    }

    @Override
    public void delete(UUID userId) {}

    @Override
    public int deleteSignedInLastBefore(Instant cutoff) {
      return 0;
    }
  }

  final RecordingUsers users = new RecordingUsers();
  final SignedInUser currentUser = new SignedInUser(users);

  static OAuth2AuthenticationToken session(String subject) {
    return new OAuth2AuthenticationToken(
        new DefaultOAuth2User(USER, Map.of("id", subject, "name", "Someone"), "id"),
        USER,
        "github");
  }

  static void holds(OAuth2AuthenticationToken session) {
    SecurityContextHolder.getContext().setAuthentication(session);
  }

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void aSessionWithoutAnAccountMustSignInAgainAndNothingIsWritten() {
    holds(session("42"));

    assertThatThrownBy(currentUser::id)
        .isInstanceOfSatisfying(
            SignInAgain.class,
            problem -> assertThat(problem.type()).isEqualTo(ProblemType.SIGN_IN_AGAIN));
    assertThat(users.signIns).isEmpty();
    assertThat(users.accounts).isEmpty();
  }

  @Test
  void aSessionWithAnAccountReadsItsId() {
    UUID id = users.signedIn("github", "42", "Someone");
    users.signIns.clear();
    holds(session("42"));

    assertThat(currentUser.id()).contains(id);
    assertThat(currentUser.displayName()).contains("Someone");
    assertThat(users.signIns).isEmpty();
  }

  @Test
  void aVisitorHasNoIdAndIsNotAskedToSignInAgain() {
    assertThat(currentUser.id()).isEmpty();
    SecurityContextHolder.getContext()
        .setAuthentication(new AnonymousAuthenticationToken("key", "anonymous", USER));
    assertThat(currentUser.id()).isEmpty();
    assertThat(currentUser.displayName()).isEmpty();
  }

  @Test
  void onlyTheSignInWritesTheAccount() {
    currentUser.signedIn(new InteractiveAuthenticationSuccessEvent(session("42"), getClass()));

    assertThat(users.signIns).containsExactly("github:42:Someone");
  }
}
