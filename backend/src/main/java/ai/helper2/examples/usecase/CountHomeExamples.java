package ai.helper2.examples.usecase;

import ai.helper2.examples.HomeExamples;
import ai.helper2.examples.PoolCounts;
import org.springframework.stereotype.Service;

/** How many examples the home page may show per path, and how many are hidden and why. */
@Service
public class CountHomeExamples {

  private final HomeExamples examples;

  public CountHomeExamples(HomeExamples examples) {
    this.examples = examples;
  }

  /** Every example, of every locale, like the back office's Examples page. */
  public PoolCounts count() {
    return PoolCounts.Companion.of(examples.all(null));
  }
}
