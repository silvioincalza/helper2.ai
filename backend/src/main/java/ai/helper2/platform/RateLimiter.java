package ai.helper2.platform;

/**
 * Fixed-window rate limit per client, keyed by {@link ClientKey}. A window starts with a client's first request and
 * lasts the limiter's window; past the limit, requests are refused until it ends.
 */
public interface RateLimiter {

    /** @return seconds to wait (at least 1), or 0 if allowed. */
    long acquire(String clientAddress);
}
