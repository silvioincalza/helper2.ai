package ai.helper2.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(
    properties = {"helper2.scout.enabled=true", "helper2.scout.initial-delay=PT24H"})
class SchedulesIT extends ApiTestBase {

  @Autowired ScheduledTaskHolder tasks;

  @Autowired ApplicationContext context;

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
  void theBackOfficeRunsTheScoutTheCatalogsJobsTheHomeExampleCheckAndTheAuditRetention() {
    assertThat(jobs(tasks))
        .containsExactlyInAnyOrder(
            "job.retention-audit",
            "job.retention-catalog",
            "job.home-examples-pending",
            "job.home-examples-nightly",
            "job.retention-runtime-settings",
            "job.retention-scout",
            "job.scout-tick");
  }

  @Test
  void theCatalogsStartupBackfillIsHere() throws ClassNotFoundException {
    assertThat(
            context.getBeanNamesForType(
                Class.forName("ai.helper2.catalog.schedule.CategoryBackfillAtStartup")))
        .hasSize(1);
  }
}
