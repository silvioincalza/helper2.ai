package ai.searchavo.catalog.schedule;

import ai.searchavo.catalog.config.IndexNowProperties;
import ai.searchavo.catalog.usecase.SendChangedPages;
import ai.searchavo.platform.ClusterLock;
import ai.searchavo.platform.ClusterLocks;
import ai.searchavo.platform.ScheduledJob;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

@Component
class IndexNowSchedule implements SchedulingConfigurer {

  private static final Logger log = LoggerFactory.getLogger(IndexNowSchedule.class);

  private final SendChangedPages send;
  private final MeterRegistry meters;
  private final ClusterLock lock;
  private final String cron;

  IndexNowSchedule(
      SendChangedPages send, MeterRegistry meters, ClusterLocks locks, IndexNowProperties p) {
    this.send = send;
    this.meters = meters;
    this.lock = locks.keptAlive("indexnow", Duration.ofMinutes(1));
    this.cron = p.cron();
  }

  @Override
  public void configureTasks(ScheduledTaskRegistrar registrar) {
    registrar.addCronTask(
        new CronTask(
            new ScheduledJob("indexnow", () -> lock.tryRun(this::run)),
            new CronTrigger(cron, ZoneOffset.UTC)));
  }

  void run() {
    try {
      switch (send.send()) {
        case SendChangedPages.Outcome.Off off -> {}
        case SendChangedPages.Outcome.NothingChanged nothing -> count("nothing");
        case SendChangedPages.Outcome.Sent sent -> {
          count("sent");
          meters.counter("searchavo.indexnow.urls").increment(sent.urls());
          log.info("indexnow: sent {} changed pages", sent.urls());
        }
      }
    } catch (RestClientException e) {
      count("failed");
      log.warn("indexnow: the batch was not accepted: {}", e.getClass().getSimpleName());
    }
  }

  private void count(String outcome) {
    meters.counter("searchavo.indexnow.batches", "outcome", outcome).increment();
  }
}
