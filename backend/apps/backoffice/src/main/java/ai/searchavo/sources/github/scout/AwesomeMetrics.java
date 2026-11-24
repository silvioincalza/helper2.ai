package ai.searchavo.sources.github.scout;

import io.micrometer.core.instrument.MeterRegistry;

final class AwesomeMetrics {

  static final String LISTS = "searchavo.scout.awesome.lists";
  static final String CANDIDATES = "searchavo.scout.awesome.candidates";
  static final String MODELS = "searchavo.scout.awesome.models";
  static final String PAPERS = "searchavo.scout.awesome.papers";
  static final String PACKAGES = "searchavo.scout.awesome.packages";
  static final String SKIPS = "searchavo.scout.awesome.skips";

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

  void modelRead() {
    meters.counter(MODELS).increment();
  }

  void papersRead(int papers) {
    meters.counter(PAPERS).increment(papers);
  }

  void packageRead() {
    meters.counter(PACKAGES).increment();
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
