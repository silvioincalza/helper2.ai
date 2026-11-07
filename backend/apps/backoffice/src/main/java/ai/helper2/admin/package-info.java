/**
 * Back office (ADR 0024, ADR 0026): the admin pages' API. Thin: it composes the other modules' use
 * cases and query ports (catalog review queue, Scout runs and backlog, model budgets, web credits)
 * and owns no table. No module depends on it. The base package holds the port of its use cases
 * (ExampleDrafts); use cases are plain classes in usecase, wired in config; the JSON reader of
 * drafts is in internal.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Back office")
package ai.helper2.admin;
