package ai.searchavo.identity;

import java.time.Duration;

public record AccountPolicy(Duration inactiveAccounts, Duration adminRecords) {}
