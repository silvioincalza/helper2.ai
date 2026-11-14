package ai.helper2.identity;

import java.time.Duration;

public record AccountPolicy(Duration inactiveAccounts, Duration adminRecords) {}
