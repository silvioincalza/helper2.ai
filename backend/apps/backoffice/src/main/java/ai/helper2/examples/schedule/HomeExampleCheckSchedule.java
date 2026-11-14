package ai.helper2.examples.schedule;

import ai.helper2.examples.config.ExamplesProperties;
import ai.helper2.examples.usecase.CheckHomeExamples;
import ai.helper2.platform.ClusterLock;
import ai.helper2.platform.ClusterLocks;
import ai.helper2.platform.ScheduledJob;
import java.time.Duration;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

@Component
class HomeExampleCheckSchedule implements SchedulingConfigurer {

  private static final Logger log = LoggerFactory.getLogger(HomeExampleCheckSchedule.class);

  private final CheckHomeExamples check;
  private final ClusterLock lock;
  private final ExamplesProperties examples;

  HomeExampleCheckSchedule(
      CheckHomeExamples check, ClusterLocks locks, ExamplesProperties examples) {
    this.check = check;
    this.lock = locks.keptAlive("home-examples-check", Duration.ofMinutes(1));
    this.examples = examples;
  }

  @Override
  public void configureTasks(ScheduledTaskRegistrar registrar) {
    registrar.addFixedDelayTask(
        new FixedDelayTask(
            new ScheduledJob("home-examples-pending", this::pending),
            examples.pendingEvery(),
            examples.pendingDelay()));
    registrar.addCronTask(
        new CronTask(
            new ScheduledJob("home-examples-nightly", this::nightly),
            new CronTrigger(examples.cron(), ZoneOffset.UTC)));
  }

  void pending() {
    run(check::checkPending);
  }

  void nightly() {
    run(check::checkAll);
  }

  private void run(Runnable task) {
    try {
      lock.tryRun(task);
    } catch (RuntimeException e) {
      log.warn("home example check failed: {}", e.getClass().getSimpleName());
    }
  }
}
