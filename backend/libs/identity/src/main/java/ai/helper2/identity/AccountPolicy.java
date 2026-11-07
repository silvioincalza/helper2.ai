package ai.helper2.identity;

import java.time.Duration;

/**
 * How long identity keeps an account and an administrator's records, as the Privacy page states it
 * (GET /api/v1/policy, ADR 0029).
 *
 * @param inactiveAccounts an account, its settings and its bookmarks after the last sign-in
 * @param adminRecords an administrator's action in the audit trail, with their id and reason
 */
public record AccountPolicy(Duration inactiveAccounts, Duration adminRecords) {}
