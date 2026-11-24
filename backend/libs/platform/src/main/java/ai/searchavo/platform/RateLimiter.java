package ai.searchavo.platform;

public interface RateLimiter {
  long acquire(String clientAddress);
}
