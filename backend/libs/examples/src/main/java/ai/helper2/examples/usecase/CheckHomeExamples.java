package ai.helper2.examples.usecase;

import ai.helper2.examples.CheckOutcome;
import ai.helper2.examples.CheckResult;
import ai.helper2.examples.ExampleCheck;
import ai.helper2.examples.ExampleSearch;
import ai.helper2.examples.HomeExample;
import ai.helper2.examples.HomeExamples;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The quality check (ADR 0015): each example runs its own search, without web search, and is shown
 * only when an expected result is in the top 3. A degraded search keeps the last result. One
 * example failing to search never stops the others.
 */
@Service
public class CheckHomeExamples {

  private static final Logger log = LoggerFactory.getLogger(CheckHomeExamples.class);

  private final HomeExamples examples;
  private final ExampleSearch search;
  private final Clock clock;
  private final MeterRegistry meters;

  public CheckHomeExamples(
      HomeExamples examples, ExampleSearch search, Clock clock, MeterRegistry meters) {
    this.examples = examples;
    this.search = search;
    this.clock = clock;
    this.meters = meters;
  }

  /** New and changed examples: they are hidden until checked. */
  public void checkPending() {
    examples.all(CheckOutcome.PENDING).forEach(this::check);
  }

  /** Every example: a ranking or catalog change can make one pass or fail. */
  public void checkAll() {
    List<HomeExample> all = examples.all(null);
    all.forEach(this::check);
    log.info("home examples checked: {}", all.size());
  }

  private void check(HomeExample example) {
    try {
      CheckResult result =
          ExampleCheck.INSTANCE.of(
              search.search(example.getQuery(), example.getPath()),
              example.getExpectedResourceIds(),
              clock.instant());
      String counted = result == null ? "degraded" : result.getOutcome().name().toLowerCase();
      if (result != null) {
        examples.recordCheck(example, result);
      }
      meters.counter("helper2.examples.checks", "outcome", counted).increment();
    } catch (RuntimeException e) {
      meters.counter("helper2.examples.checks", "outcome", "error").increment();
      log.warn("home example {} not checked: {}", example.getId(), e.getClass().getSimpleName());
    }
  }
}
