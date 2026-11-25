package ai.searchavo.testing;

import java.time.Duration;

public final class Eventually {

  private static final Duration LIMIT = Duration.ofSeconds(20);

  private static final Duration PAUSE = Duration.ofMillis(10);

  private Eventually() {}

  @FunctionalInterface
  public interface Check {
    void run() throws Exception;
  }

  public static void eventually(Check check) throws Exception {
    long giveUp = System.nanoTime() + LIMIT.toNanos();
    while (true) {
      try {
        check.run();
        return;
      } catch (AssertionError failed) {
        if (System.nanoTime() > giveUp) {
          throw failed;
        }
        Thread.sleep(PAUSE);
      }
    }
  }
}
