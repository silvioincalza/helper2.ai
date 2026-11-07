/**
 * Policy (ADR 0029): GET /api/v1/policy, the limits and retention periods the Mission, Privacy and
 * Terms pages state, as the running configuration sets them. Thin: it reads the policy value each
 * owning module publishes (search, guide, identity, catalog) and owns no table. No module depends
 * on it.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Policy")
package ai.helper2.policy;
