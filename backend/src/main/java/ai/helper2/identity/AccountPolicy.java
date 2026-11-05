package ai.helper2.identity;

import java.time.Duration;

/**
 * How long identity keeps an account, as the Privacy page states it (GET /api/v1/policy, ADR 0051).
 *
 * @param inactiveAccounts an account, its settings and its bookmarks after the last sign-in
 */
public record AccountPolicy(Duration inactiveAccounts) {}
