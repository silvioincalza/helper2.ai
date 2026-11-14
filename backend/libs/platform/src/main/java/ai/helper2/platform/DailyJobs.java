package ai.helper2.platform;

import ai.helper2.platform.config.RetentionSchedule;
import java.time.Duration;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.CronTrigger;

@Configuration(proxyBeanMethods = false)
class DailyJobs implements SchedulingConfigurer {

  private static final Logger log = LoggerFactory.getLogger(DailyJobs.class);

  private static final Duration LEASE = Duration.ofMinutes(1);

  private final ObjectProvider<DailyJob> jobs;
  private final ObjectProvider<RetentionSchedule> schedule;
  private final ObjectProvider<ClusterLocks> locks;

  DailyJobs(
      ObjectProvider<DailyJob> jobs,
      ObjectProvider<RetentionSchedule> schedule,
      ObjectProvider<ClusterLocks> locks) {
    this.jobs = jobs;
    this.schedule = schedule;
    this.locks = locks;
  }

  @Override
  public void configureTasks(ScheduledTaskRegistrar registrar) {
    jobs.orderedStream()
        .forEach(job ->
            registrar.addCronTask(
                new CronTask(
                    locked(job), new CronTrigger(schedule.getObject().cron(), ZoneOffset.UTC))));
  }

  private ScheduledJob locked(DailyJob job) {
    String name = "retention-" + job.name();
    ClusterLock lock = locks.getObject().keptAlive(name, LEASE);
    return new ScheduledJob(
        name,
        () -> {
          try {
            lock.tryRun(job.task());
          } catch (RuntimeException e) {
            log.warn("{} retention failed: {}", job.name(), e.getClass().getSimpleName());
          }
        });
  }
}
