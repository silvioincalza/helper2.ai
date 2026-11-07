package ai.helper2.catalog.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class EmbeddingIndexerLeaseTest {

  /**
   * The cluster lock is not renewed, so its lease covers every call a tick can make: the batch and
   * the single texts after a failure, each at its worst (wait, connect, read: 3 × the timeout),
   * plus a minute for the tick's short queries. A lease sized for one call let a second replica
   * take the lock while the first was still on its singles.
   */
  @Test
  void theLeaseCoversTheBatchAndEverySingleTextAfterAFailure() {
    Duration timeout = Duration.ofSeconds(30);

    assertThat(CatalogConfiguration.VectorSearch.tickWorstCase(timeout, 0))
        .isEqualTo(Duration.ofSeconds(150));
    assertThat(CatalogConfiguration.VectorSearch.tickWorstCase(timeout, 5))
        .isEqualTo(Duration.ofMinutes(10));
  }
}
