package ai.helper2.identity;

/**
 * Whether the request being served came in on the separate admin port (ADR 0035). False without an
 * admin port, and outside a request. GET /me uses it to keep public-only features, such as
 * analytics and ads (ADR 0057), off the back office host.
 */
@FunctionalInterface
public interface AdminHost {

  boolean currentRequestOnAdminHost();
}
