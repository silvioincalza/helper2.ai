package ai.searchavo.examples.usecase;

import ai.searchavo.examples.HomeExamples;
import ai.searchavo.examples.PoolCounts;

public class CountHomeExamples {

  private final HomeExamples examples;

  public CountHomeExamples(HomeExamples examples) {
    this.examples = examples;
  }

  public PoolCounts count() {
    return PoolCounts.Companion.of(examples.all(null));
  }
}
