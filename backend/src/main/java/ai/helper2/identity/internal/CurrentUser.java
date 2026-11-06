package ai.helper2.identity.internal;

import ai.helper2.identity.Users;
import ai.helper2.platform.SignedInUser;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.InteractiveAuthenticationSuccessEvent;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Resolves the signed-in user (if any) to the local app_user id. The row is written once per
 * sign-in (which also refreshes the display name and the last sign-in that retention reads); later
 * calls only read it.
 */
@Component
public class CurrentUser implements SignedInUser {

  private final Users users;

  CurrentUser(Users users) {
    this.users = users;
  }

  @Override
  public Optional<UUID> id() {
    Authentication a = SecurityContextHolder.getContext().getAuthentication();
    if (a instanceof OAuth2AuthenticationToken t && t.isAuthenticated()) {
      String provider = t.getAuthorizedClientRegistrationId();
      // A session from before the row existed (e.g. a recreated database) still gets one.
      return Optional.of(
          users
              .find(provider, t.getName())
              .orElseGet(() -> users.signedIn(provider, t.getName(), displayName(t))));
    }
    return Optional.empty();
  }

  /**
   * The sign-in filter publishes this event with the session's token. (The provider manager's
   * {@code AuthenticationSuccessEvent} carries the login token, another type, and is not it.)
   */
  @EventListener
  void signedIn(InteractiveAuthenticationSuccessEvent event) {
    if (event.getAuthentication() instanceof OAuth2AuthenticationToken t) {
      users.signedIn(t.getAuthorizedClientRegistrationId(), t.getName(), displayName(t));
    }
  }

  public static String displayName(OAuth2AuthenticationToken t) {
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
