package ai.helper2.platform;

public record ScheduledJob(String name, Runnable task) implements Runnable {

  public static final String PREFIX = "ai.helper2.job.";

  @Override
  public void run() {
    task.run();
  }

  @Override
  public String toString() {
    return PREFIX + name;
  }
}
