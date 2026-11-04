/**
 * Back office (ADR 0023, ADR 0035, ADR 0045): the admin pages' API. Thin: it composes the other
 * modules' use cases and query ports (catalog review queue, Scout runs and backlog, model budgets,
 * web credits) and owns no table. No module depends on it.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Back office")
package ai.helper2.admin;
