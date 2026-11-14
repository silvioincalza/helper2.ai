package ai.helper2.platform.resilience;

import java.time.Duration;

@FunctionalInterface
public interface OutboundThrottle {
  boolean tryAcquire(String host, Duration interval);
}
