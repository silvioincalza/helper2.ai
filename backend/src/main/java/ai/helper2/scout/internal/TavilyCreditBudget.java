package ai.helper2.scout.internal;

import ai.helper2.platform.llm.DailyBudget;
import ai.helper2.scout.ExtractBudget;
import ai.helper2.search.QuotaDecision;
import ai.helper2.search.WebSearchQuota;
import io.micrometer.core.instrument.MeterRegistry;
import org.jspecify.annotations.Nullable;

/**
 * Tavily Extract credits for the evidence ladder (ADR 0056). Each credit takes one unit of the page
 * step's own daily cap, then one of the global Tavily credits every live search counts on (ADR
 * 0037), so the Scout cannot spend what visitors need. When the global counter cannot answer, no
 * page is read: credits are not spent unguarded. Counted as {@code
 * helper2.tavily.credits{feature=scout-evidence,tier=none}}, like a live search.
 */
final class TavilyCreditBudget implements ExtractBudget {

  static final String FEATURE = "scout-evidence";

  private final DailyBudget own;
  private final @Nullable WebSearchQuota global;
  private final MeterRegistry meters;

  TavilyCreditBudget(DailyBudget own, @Nullable WebSearchQuota global, MeterRegistry meters) {
    this.own = own;
    this.global = global;
    this.meters = meters;
  }

  @Override
  public boolean tryTake() {
    if (!own.tryAcquire()) {
      return false;
    }
    if (global != null && !(global.take(null) instanceof QuotaDecision.Allowed)) {
      // The unit of the page step's own cap stays taken: that errs towards the cap.
      return false;
    }
    meters.counter("helper2.tavily.credits", "feature", FEATURE, "tier", "none").increment();
    return true;
  }
}
