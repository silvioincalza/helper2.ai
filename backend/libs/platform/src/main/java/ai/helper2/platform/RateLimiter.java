package ai.helper2.platform;

public interface RateLimiter {
  long acquire(String clientAddress);
}
