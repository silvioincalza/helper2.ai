package ai.helper2.sources.support;

import ai.helper2.search.SourceFailure;
import java.time.Duration;
import java.util.function.Predicate;

/**
 * What a collector policy retries (resilience4j.retry.configs.collector, ADR 0033): a 503 or a network error or
 * timeout ({@link SourceFailure.Unavailable#getTransient()}), and a rate limit (429) that asks for at most
 * {@link #LONGEST_PAUSE} or names no pause. A longer pause (GitHub's hourly reset) is not worth waiting for in a run.
 */
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
