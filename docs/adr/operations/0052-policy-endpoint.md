# 0052 — Policy endpoint: the limits and retention periods the text pages state, read from the configuration

**Status.** Accepted. **Builds on:** [0003](../foundation/0003-contract-first-rest-api.md), [0007](../foundation/0007-configuration-and-secrets.md), [0017](../guide/0017-guide-cost-guard.md), [0024](../edge/0024-http-edge-protection.md), [0034](0034-data-retention.md), [0037](../search/0037-tavily-store-and-daily-quota.md)

## Context
The Mission, Privacy and Terms pages state numbers: how many candidates the guide reads, how many picks it gives, the
daily limits, and how long each store keeps data. The pages type them in. The code reads the same numbers from
configuration, and every one can change with an `H2_` variable, without a release. So a page can say "30 questions a
day" while the server allows 50, and nothing fails. For the Privacy page that is a legal problem, not only a bug.

## Decision
1. **One public read: `GET /api/v1/policy`.** It returns the numbers the text pages state, as the running server
   applies them. No sign-in, no cookie, no personal data: the same answer for everyone.
2. **What it holds.** Only values the code reads from configuration, or a rule fixed in code that a page states:
   - `guide`: `maxCandidates` (`H2_GUIDE_MAX_CANDIDATES`) and `maxPicks` (`GuideValidator.MAX_PICKS`, fixed in code).
   - `dailyLimits`: `guideQuestions` (`H2_GUIDE_CLIENT_DAILY_QUESTIONS`) and `webSearches` per feature for guests and
     for signed-in users. `registered` is null while the registered tier is off, because everyone then gets the guest
     limits.
   - `retention`, in seconds: cached searches (`H2_SEARCH_STALE_FOR`), cached guide answers (`H2_GUIDE_CACHE_TTL`),
     translations (`H2_SEARCH_TRANSLATION_TTL`), query vectors (`H2_SEARCH_QUERY_VECTOR_TTL`), stored web results
     (`H2_RETENTION_TAVILY_RESULTS`), inactive accounts with their saved items (`H2_RETENTION_INACTIVE_ACCOUNTS`),
     handled reports (`H2_RETENTION_RESOLVED_ISSUE_REPORTS`) and administrators' records in the audit trail
     (`H2_RETENTION_ADMIN_AUDIT`, [0045](../edge/0045-back-office.md)), and runtime setting changes
     (`H2_RETENTION_RUNTIME_SETTINGS`, [0060](0060-runtime-settings.md); each setting's newest change stays while it
     applies).

   Seconds, not ISO-8601: `Duration.toString()` writes 730 days as `PT17520H`, and a page would need a parser. The
   page rounds seconds to hours, days or months.
3. **Each module publishes its own numbers.** The module that owns a setting builds one small value from its
   `@ConfigurationProperties` records at startup, in its base package: `search.SearchPolicy`, `guide.GuidePolicy`,
   `identity.AccountPolicy`, `catalog.CatalogPolicy`. The config records stay internal to their module.
4. **A thin `policy` module serves them.** Like the back office, it composes other modules and owns no table; no module
   depends on it. `policy.web.PolicyController` maps the four values to the contract. There is no use case: the
   endpoint makes no decision.
5. **Cached and rate-limited.** `Cache-Control: public, max-age=300`, like the home examples. The values change only
   with a restart, so 5 minutes of a stale number after a deploy is acceptable. No ETag: the body is a few hundred
   bytes, and no endpoint of the API has one yet. The `policy` limiter allows `H2_RATE_LIMIT_RECENT_PER_MINUTE`
   requests per client address and minute, like the other public reads.
6. **Public in the security chain.** `/api/v1/policy` is in the `permitAll` list of `SecurityConfiguration`. It is
   not a page, so the pages for crawlers do not use it.

## Consequences
- A text page can show the number the server applies. A changed `H2_` variable changes the page with the next cache
  refresh, with no release of the frontend.
- A new setting that a page states needs one field in the contract, in the owning module's policy value and in the
  controller.
- Values fixed in code that are not in the response (the counter lifetimes, the rate-limit windows, the 6-hour
  lifetime of a dated guide answer) and values the code does not know (log retention, backups) stay typed in the pages.

## Alternatives considered
- Keep the numbers typed in the pages — no code, but nothing tells when a page and the server disagree.
- The policy module reads the config records of the other modules — fewer types, but the records are internal to their
  modules (Spring Modulith), and the controller would depend on how each module binds its settings.
- Numbers in each existing response (`limits` on GET /me, and so on) — no new endpoint, but the text pages would call
  endpoints made for other screens, and retention periods belong to no screen.
- A build-time step that writes the numbers into the frontend bundle — no runtime call, but it bakes the defaults, not
  the values of the running cluster.

## Code
- `api/openapi.yaml` — `GET /policy`, schemas `Policy` and `WebSearchLimits`.
- `backend/src/main/java/ai/helper2/policy/` — the module and `web/PolicyController.java`.
- `backend/src/main/kotlin/ai/helper2/search/SearchPolicy.kt`, `guide/GuidePolicy.kt`,
  `backend/src/main/java/ai/helper2/identity/AccountPolicy.java`, `catalog/CatalogPolicy.java` — the values; built in
  `search/config/SearchConfiguration.java`, `guide/internal/GuideConfiguration.java`,
  `identity/config/IdentityPolicyConfiguration.java`, `catalog/internal/CatalogConfiguration.java`.
- `backend/src/main/java/ai/helper2/platform/RateLimitInterceptor.java` — the `policy` limiter.
- `backend/src/main/java/ai/helper2/identity/config/SecurityConfiguration.java` — the public path.
- `backend/src/test/java/ai/helper2/api/PolicyApiIT.java` — contract, cache header, values from configuration, rate limit.

## Diagrams
[flow](../../diagrams/flow-0052-policy-endpoint.svg) · [sequence](../../diagrams/sequence-0052-policy-endpoint.svg)

## Frontend
The Mission, Privacy and Terms pages read the numbers from `GET /api/v1/policy`
([0042](../frontend/0042-redesign-design-system-v4.md), decision 13): `frontend/src/hooks/usePolicy.ts`,
`lib/duration.ts`, `components/TextSections.tsx`, `routes.tsx`.
