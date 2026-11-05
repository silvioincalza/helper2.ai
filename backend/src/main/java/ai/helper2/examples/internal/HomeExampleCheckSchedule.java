package ai.helper2.examples.internal;

import ai.helper2.examples.usecase.CheckHomeExamples;
import ai.helper2.platform.ClusterLock;
import ai.helper2.platform.ClusterLocks;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the quality check on one replica at a time: new and changed examples every few minutes
 * (helper2.examples.pending-every), every example once a night (helper2.examples.cron, UTC).
 */
@Component
class HomeExampleCheckSchedule {

  private static final Logger log = LoggerFactory.getLogger(HomeExampleCheckSchedule.class);

  private final CheckHomeExamples check;
  private final ClusterLock lock;

  HomeExampleCheckSchedule(CheckHomeExamples check, ClusterLocks locks) {
    this.check = check;
    this.lock = locks.keptAlive("home-examples-check", Duration.ofMinutes(1));
  }

  @Scheduled(
      initialDelayString = "${helper2.examples.pending-delay}",
      fixedDelayString = "${helper2.examples.pending-every}")
  void pending() {
    run(check::checkPending);
  }

  @Scheduled(cron = "${helper2.examples.cron}", zone = "UTC")
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
