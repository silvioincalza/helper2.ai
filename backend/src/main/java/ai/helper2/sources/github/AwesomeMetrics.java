package ai.helper2.sources.github;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * What the awesome-list collector did in a pass (ADR 0058): lists read (seed or nested), links
 * proposed for review, and everything it skipped, by a reason from a fixed set.
 */
final class AwesomeMetrics {

  static final String LISTS = "helper2.scout.awesome.lists";
  static final String CANDIDATES = "helper2.scout.awesome.candidates";
  static final String SKIPS = "helper2.scout.awesome.skips";

  private final MeterRegistry meters;

  AwesomeMetrics(MeterRegistry meters) {
    this.meters = meters;
  }

  void listRead(int depth) {
    meters.counter(LISTS, "depth", depth == 0 ? "seed" : "nested").increment();
  }

  void proposed(int candidates) {
    meters.counter(CANDIDATES).increment(candidates);
  }

  void skipped(String reason) {
    skipped(reason, 1);
  }

  void skipped(String reason, int count) {
    if (count > 0) {
      meters.counter(SKIPS, "reason", reason).increment(count);
    }
  }
}
