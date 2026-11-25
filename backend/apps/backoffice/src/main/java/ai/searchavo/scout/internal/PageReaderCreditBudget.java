package ai.searchavo.scout.internal;

import ai.searchavo.platform.llm.DailyBudget;
import ai.searchavo.scout.ExtractBudget;
import ai.searchavo.search.QuotaDecision;
import ai.searchavo.search.WebSearchQuota;
import io.micrometer.core.instrument.MeterRegistry;
import org.jspecify.annotations.Nullable;

final class PageReaderCreditBudget implements ExtractBudget {

  static final String FEATURE = "scout-evidence";

  private final DailyBudget own;
  private final @Nullable WebSearchQuota global;
  private final MeterRegistry meters;

  PageReaderCreditBudget(DailyBudget own, @Nullable WebSearchQuota global, MeterRegistry meters) {
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
      return false;
    }
    meters.counter("searchavo.live-web.credits", "feature", FEATURE).increment();
    return true;
  }
}
