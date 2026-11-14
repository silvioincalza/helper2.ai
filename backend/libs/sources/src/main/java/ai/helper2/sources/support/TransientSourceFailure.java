package ai.helper2.sources.support;

import ai.helper2.search.SourceFailure;
import java.time.Duration;
import java.util.function.Predicate;

public final class TransientSourceFailure implements Predicate<Throwable> {

  static final Duration LONGEST_PAUSE = Duration.ofSeconds(10);

  @Override
  public boolean test(Throwable e) {
    if (e instanceof SourceFailure.Unavailable u) {
      return u.getTransient();
    }
    if (e instanceof SourceFailure.RateLimited r) {
      return r.getRetryAfter() == null || r.getRetryAfter().compareTo(LONGEST_PAUSE) <= 0;
    }
    return false;
  }
}
