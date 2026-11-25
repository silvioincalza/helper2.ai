package ai.searchavo.scout.internal;

import ai.searchavo.catalog.enrichment.RepositoryVisits;
import ai.searchavo.catalog.usecase.ForgetGoneLists;
import ai.searchavo.platform.ClusterLock;
import ai.searchavo.platform.ClusterLocks;
import ai.searchavo.platform.ScheduledJob;
import ai.searchavo.scout.GoneWebPageRule;
import ai.searchavo.scout.config.ScoutProperties;
import ai.searchavo.scout.usecase.RepositoryStates;
import ai.searchavo.scout.usecase.SweepRepositories;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

@Configuration(proxyBeanMethods = false)
class RepositorySweepSchedule implements SchedulingConfigurer {

  private static final Logger log = LoggerFactory.getLogger(RepositorySweepSchedule.class);

  private static final Duration LEASE = Duration.ofMinutes(1);

  private final ScoutProperties.RepositorySweep settings;
  private final @Nullable SweepRepositories sweep;
  private final ClusterLock lock;
  private final MeterRegistry meters;

  RepositorySweepSchedule(
      ScoutProperties scout,
      ObjectProvider<RepositoryStates> states,
      RepositoryVisits visits,
      ForgetGoneLists goneLists,
      ClusterLocks locks,
      Clock clock,
      MeterRegistry meters) {
    this.settings = scout.repositorySweep();
    RepositoryStates github = states.getIfAvailable();
    this.sweep =
        settings.enabled() && github != null
            ? new SweepRepositories(
                visits,
                github,
                clock,
                settings.batch(),
                meters,
                notFound ->
                    meters
                        .counter(GoneWebPageRule.FORGOTTEN, "kind", "github")
                        .increment(goneLists.forget(notFound, settings.goneListVisits())))
            : null;
    this.lock = locks.keptAlive("scout-repository-sweep", LEASE);
    this.meters = meters;
    log.info("repository sweep: on={}", sweep != null);
  }

  @Override
  public void configureTasks(ScheduledTaskRegistrar registrar) {
    if (sweep == null) {
      return;
    }
    registrar.addFixedDelayTask(
        new FixedDelayTask(
            new ScheduledJob("scout-repository-sweep", this::sweepOneBatch),
            settings.every(),
            settings.initialDelay()));
  }

  void sweepOneBatch() {
    SweepRepositories due = sweep;
    if (due == null) {
      return;
    }
    try {
      lock.tryRun(due::sweepOneBatch);
    } catch (RuntimeException e) {
      meters.counter("searchavo.scout.repository_sweep", "outcome", "failed").increment();
      log.warn("repository sweep failed: {}", e.getClass().getSimpleName());
    }
  }
}
