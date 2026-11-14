package ai.helper2.examples.usecase;

import ai.helper2.examples.HomeExamples;
import ai.helper2.examples.PoolCounts;

public class CountHomeExamples {

  private final HomeExamples examples;

  public CountHomeExamples(HomeExamples examples) {
    this.examples = examples;
  }

  public PoolCounts count() {
    return PoolCounts.Companion.of(examples.all(null));
  }
}
