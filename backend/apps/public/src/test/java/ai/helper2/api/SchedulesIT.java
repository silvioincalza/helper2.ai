package ai.helper2.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.config.ScheduledTaskHolder;

/**
 * Every job runs in exactly one app (ADR 0069): these are the public app's. The back office's
 * SchedulesIT lists its own, and no job is in both. Each replica's runtime-settings refresh is a
 * thread of the platform library in both apps: it reads, it does no shared work.
 */
class SchedulesIT extends ApiTestBase {

  @Autowired ScheduledTaskHolder tasks;

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
  void thePublicAppRunsTheRetentionOfWhatVisitorsLeave() {
    assertThat(jobs(tasks))
        .containsExactlyInAnyOrder(
            "GuideRetentionSchedule.Daily.run",
            "IdentityRetentionSchedule.daily",
            "SearchRetentionSchedule.Daily.run",
            "SharedCacheRetentionSchedule.run");
  }

  /** The catalog's jobs, the Scout and the home-example check are not even on the classpath. */
  @Test
  void theBackOfficeJobsAreNotHere() {
    for (String job :
        List.of(
            "ai.helper2.catalog.internal.EmbeddingIndexerSchedule",
            "ai.helper2.catalog.internal.CatalogRetentionSchedule",
            "ai.helper2.catalog.internal.CategoryBackfillAtStartup",
            "ai.helper2.platform.settings.RuntimeSettingsPurgeSchedule",
            "ai.helper2.examples.internal.HomeExampleCheckSchedule",
            "ai.helper2.scout.internal.ScoutConfiguration")) {
      assertThat(getClass().getClassLoader().getResource(job.replace('.', '/') + ".class"))
          .as(job)
          .isNull();
    }
  }
}
