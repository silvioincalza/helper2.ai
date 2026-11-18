package ai.helper2.research.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FindLatestResearchTest {

  @Test
  void theLatestRunsComeFromTheStoreOfRuns() {
    RunResearchTest.Runs runs = new RunResearchTest().runs;

    assertThat(new FindLatestResearch(runs).of(List.of(UUID.randomUUID()))).isEmpty();
  }
}
