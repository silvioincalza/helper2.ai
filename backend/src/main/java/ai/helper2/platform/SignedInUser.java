package ai.helper2.platform;

import java.util.Optional;
import java.util.UUID;

/** The signed-in user of the current request, as the local account id; empty for an anonymous visitor. */
@FunctionalInterface
public interface SignedInUser {

    /** No one signs in (adapters tested without identity). */
    SignedInUser ANONYMOUS = Optional::empty;

    Optional<UUID> id();
}
