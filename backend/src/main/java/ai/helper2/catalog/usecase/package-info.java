/**
 * Use cases of the catalog: one entry point per thing a caller can ask the module to do. Plain Spring
 * services over SQL (the catalog is not hexagonal). Exposed as a named interface for the setup guide
 * (resource details) and the admin page (review decisions).
 */
@org.springframework.modulith.NamedInterface("usecase")
package ai.helper2.catalog.usecase;
