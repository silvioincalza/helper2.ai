/**
 * Use cases of the identity module, one per thing a signed-in user or the retention job can ask of
 * it. Plain classes over the module's ports (Users, Bookmarks, AuditLog), wired in identity.config;
 * the web adapters only map requests to them.
 */
package ai.helper2.identity.usecase;
