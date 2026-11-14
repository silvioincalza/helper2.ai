package ai.helper2.identity;

import org.jspecify.annotations.Nullable;

public interface VisitorLimits {
  record Allowance(int limit, int remaining) {}

  record Today(
      @Nullable Allowance guideQuestions,
      @Nullable Allowance setupGuides,
      @Nullable Allowance searchWebSearches,
      @Nullable Allowance guideWebSearches) {}

  Today of(String client);
}
