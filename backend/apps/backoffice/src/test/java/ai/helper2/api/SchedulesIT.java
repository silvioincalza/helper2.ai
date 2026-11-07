package ai.helper2.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.test.context.TestPropertySource;

/**
 * Every job runs in exactly one app (ADR 0032): these are the back office's. The public app's
 * SchedulesIT lists its own, and no job is in both. The Scout's daily check is on here, far in the
 * future, so it is listed without running.
 */
@TestPropertySource(
    properties = {"helper2.scout.enabled=true", "helper2.scout.initial-delay=PT24H"})
class SchedulesIT extends ApiTestBase {

  @Autowired ScheduledTaskHolder tasks;

  @Autowired ApplicationContext context;

  /** "Class.method" of each @Scheduled method (nested classes with their outer one). */
  static List<String> jobs(ScheduledTaskHolder tasks) {
    return tasks.getScheduledTasks().stream()
        .map(t -> t.getTask().getRunnable().toString())
        // Ours only: a framework's own tasks (Spring Modulith's moments in the tests) are not jobs.
        .filter(name -> name.startsWith("ai.helper2."))
        .map(name -> {
          String method = name.substring(name.lastIndexOf('.') + 1);
          String type = name.substring(0, name.lastIndexOf('.'));
          return type.substring(type.lastIndexOf('.') + 1).replace('$', '.') + "." + method;
        })
        .sorted()
        .toList();
  }

  @Test
  void theBackOfficeRunsTheScoutTheCatalogsJobsAndTheHomeExampleCheck() {
    assertThat(jobs(tasks))
        .containsExactlyInAnyOrder(
            "CatalogRetentionSchedule.daily",
            "HomeExampleCheckSchedule.pending",
            "HomeExampleCheckSchedule.nightly",
            "RuntimeSettingsPurgeSchedule.run",
            "ScoutRetentionSchedule.Daily.run",
            "ScoutConfiguration.ScoutSchedule.tick");
  }

  /**
   * The category backfill at startup is the back office's too; the embedding indexer's thread is
   * declared here (EmbeddingIndexerSchedule) and on only with an embedding key.
   */
  @Test
  void theCatalogsStartupBackfillIsHere() throws ClassNotFoundException {
    assertThat(
            context.getBeanNamesForType(
                Class.forName("ai.helper2.catalog.internal.CategoryBackfillAtStartup")))
        .hasSize(1);
  }
}
