package ai.searchavo.platform.resilience;

import java.time.Duration;

@FunctionalInterface
public interface OutboundThrottle {
  boolean tryAcquire(String host, Duration interval);
}
