package ai.helper2.identity;

import ai.helper2.identity.internal.Users;
import ai.helper2.platform.SignedInUser;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Resolves the signed-in user (if any) to the local app_user id. The row is written once per sign-in (which also
 * refreshes the display name and the last sign-in that retention reads); later calls only read it.
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
            return Optional.of(users.find(provider, t.getName()).orElseGet(() -> users.signedIn(provider, t.getName(), displayName(t))));
        }
        return Optional.empty();
    }

    @EventListener
    void signedIn(AuthenticationSuccessEvent event) {
        if (event.getAuthentication() instanceof OAuth2AuthenticationToken t) {
            users.signedIn(t.getAuthorizedClientRegistrationId(), t.getName(), displayName(t));
        }
    }

    static String displayName(OAuth2AuthenticationToken t) {
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
