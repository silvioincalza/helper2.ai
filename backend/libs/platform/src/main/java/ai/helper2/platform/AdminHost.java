package ai.helper2.platform;

/**
 * Whether the request being served came in on the separate admin port (ADR 0035). False without an
 * admin port, and outside a request. Identity answers it; GET /me uses it to keep public-only
 * features, such as analytics and ads (ADR 0057), off the back office host, and the rate limits
 * leave that host to the network that closes it.
 */
@FunctionalInterface
public interface AdminHost {

  /** No admin port (adapters tested without identity). */
  AdminHost NONE = () -> false;

  boolean currentRequestOnAdminHost();
}
