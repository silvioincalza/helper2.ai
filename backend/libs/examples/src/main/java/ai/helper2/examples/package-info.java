/**
 * Home examples (ADR 0015): the example searches under the home box. Domain (Kotlin,
 * framework-free) at the top level: the example, its text rules and the quality check. Use cases in
 * usecase, the PostgreSQL store and the catalog lookup in internal, the public endpoint in web; the
 * back office's endpoints live in the admin module.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Home examples")
package ai.helper2.examples;
