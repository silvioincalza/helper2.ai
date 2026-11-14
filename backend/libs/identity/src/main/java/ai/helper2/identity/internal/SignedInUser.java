package ai.helper2.identity.internal;

import ai.helper2.identity.CurrentUser;
import ai.helper2.identity.SignInAgain;
import ai.helper2.identity.Users;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.InteractiveAuthenticationSuccessEvent;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Component;

@Component
class SignedInUser implements CurrentUser {

  private final Users users;

  SignedInUser(Users users) {
    this.users = users;
  }

  @Override
  public Optional<UUID> id() {
    return session().map(this::accountOf);
  }

  @Override
  public Optional<String> displayName() {
    return session().map(SignedInUser::displayName);
  }

  private UUID accountOf(OAuth2AuthenticationToken session) {
    return users
        .find(session.getAuthorizedClientRegistrationId(), session.getName())
        .orElseThrow(SignInAgain::new);
  }

  @EventListener
  void signedIn(InteractiveAuthenticationSuccessEvent event) {
    if (event.getAuthentication() instanceof OAuth2AuthenticationToken t) {
      users.signedIn(t.getAuthorizedClientRegistrationId(), t.getName(), displayName(t));
    }
  }

  private static Optional<OAuth2AuthenticationToken> session() {
    Authentication a = SecurityContextHolder.getContext().getAuthentication();
    if (a instanceof OAuth2AuthenticationToken t && t.isAuthenticated()) {
      return Optional.of(t);
    }
    return Optional.empty();
  }

  private static String displayName(OAuth2AuthenticationToken t) {
    Object name = t.getPrincipal().getAttributes().get("name");
    if (name == null) {
      name = t.getPrincipal().getAttributes().get("preferred_username");
    }
    if (name == null) {
      name = t.getPrincipal().getAttributes().get("login");
    }
    return name == null ? null : name.toString();
  }
}
