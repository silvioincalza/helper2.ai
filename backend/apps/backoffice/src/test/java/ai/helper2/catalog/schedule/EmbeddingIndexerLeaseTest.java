package ai.helper2.catalog.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class EmbeddingIndexerLeaseTest {
  @Test
  void theLeaseCoversTheBatchAndEverySingleTextAfterAFailure() {
    Duration timeout = Duration.ofSeconds(30);

    assertThat(EmbeddingIndexerSchedule.tickWorstCase(timeout, 0))
        .isEqualTo(Duration.ofSeconds(150));
    assertThat(EmbeddingIndexerSchedule.tickWorstCase(timeout, 5))
        .isEqualTo(Duration.ofMinutes(10));
  }
}
