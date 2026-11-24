package ai.searchavo.platform;

public record ScheduledJob(String name, Runnable task) implements Runnable {

  public static final String PREFIX = "ai.searchavo.job.";

  @Override
  public void run() {
    task.run();
  }

  @Override
  public String toString() {
    return PREFIX + name;
  }
}
