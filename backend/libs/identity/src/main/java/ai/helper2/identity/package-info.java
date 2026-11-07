/**
 * Identity: optional OAuth2 sign-in, ADMIN allow-list, CSRF for the SPA, server-side bookmarks,
 * account deletion and the admin audit trail (AuditTrail port, written by every admin use case).
 * The base package is the module's core: the AuditTrail port and the VisitorLimits port that GET
 * /me reads the daily limits through, which other modules use, and the ports of its own use cases
 * (Users, Bookmarks, AuditLog, RetentionPeriods). Use cases are in usecase, controllers in web,
 * security, session setup and use case wiring in config, the JDBC stores and the SignedInUser
 * adapter in internal.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Identity")
package ai.helper2.identity;
