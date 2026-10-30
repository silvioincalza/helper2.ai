package ai.helper2.platform.resilience;

import java.time.Duration;

/**
 * At most one call to a remote host per interval, across every replica: for providers whose terms set a global
 * pace (arXiv: one request every 3 seconds). Resilience4j's rate limiter counts in one JVM only, so it cannot keep it.
 */
@FunctionalInterface
public interface OutboundThrottle {

    /** True when the caller may call {@code host} now; the next caller waits {@code interval}. False also when unsure. */
    boolean tryAcquire(String host, Duration interval);
}
