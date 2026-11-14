package ai.helper2.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.config.ScheduledTaskHolder;

class SchedulesIT extends ApiTestBase {

  @Autowired ScheduledTaskHolder tasks;

  static List<String> jobs(ScheduledTaskHolder tasks) {
    return tasks.getScheduledTasks().stream()
        .map(t -> t.getTask().getRunnable().toString())
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
            "job.retention-guide",
            "job.retention-accounts",
            "job.retention-search",
            "job.retention-shared-cache");
  }

  @Test
  void theBackOfficeJobsAreNotHere() {
    for (String job :
        List.of(
            "ai.helper2.catalog.schedule.EmbeddingIndexerSchedule",
            "ai.helper2.catalog.schedule.CatalogRetentionSchedule",
            "ai.helper2.catalog.schedule.CategoryBackfillAtStartup",
            "ai.helper2.platform.settings.RuntimeSettingsPurgeSchedule",
            "ai.helper2.examples.schedule.HomeExampleCheckSchedule",
            "ai.helper2.scout.internal.ScoutConfiguration")) {
      assertThat(getClass().getClassLoader().getResource(job.replace('.', '/') + ".class"))
          .as(job)
          .isNull();
    }
  }
}
