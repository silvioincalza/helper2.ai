package ai.helper2.platform.llm;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Without a shared store the budgets still list themselves, and their counts are unknown, not guessed.
 */
class BudgetLedgerTest {

    final Clock clock = Clock.fixed(Instant.parse("2026-11-04T10:00:00Z"), ZoneOffset.UTC);

    @Test
    void everyBudgetDeclaresItselfByNameAndWithoutAStoreItsCountIsUnknown() {
        BudgetLedger ledger = new BudgetLedger(null, clock);
        DailyBudget guide = new DailyBudget(200, clock, "guide", ledger);
        new DailyBudget(500, clock, "enrichment", ledger);

        assertThat(guide.tryAcquire()).as("in memory without a store").isTrue();
        assertThat(ledger.today()).containsExactly(
            new BudgetLedger.Reading("enrichment", 500, null),
            new BudgetLedger.Reading("guide", 200, null));
    }
}
