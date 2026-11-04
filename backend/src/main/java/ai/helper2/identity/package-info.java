/**
 * Identity: optional OAuth2 sign-in, ADMIN allow-list, CSRF for the SPA, server-side bookmarks,
 * account deletion and the admin audit trail (AuditTrail port, written by every admin use case).
 * The base package is the module's API: the AuditTrail port only. Controllers are in web, security
 * and session setup in config, stores and the SignedInUser adapter in internal.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Identity")
package ai.helper2.identity;
