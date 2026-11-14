package ai.helper2.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(
    properties = {
      "helper2.embedding.api-key=test-key",
      "helper2.catalog.indexer.initial-delay=PT24H"
    })
class EmbeddingIndexerStartIT extends ApiTestBase {

  @Autowired ApplicationContext context;

  @Test
  void theBackOfficeStartsWithTheIndexerScheduleWhenAnEmbeddingKeyIsSet()
      throws ClassNotFoundException {
    assertThat(
            context.getBeanNamesForType(
                Class.forName(
                    "ai.helper2.catalog.schedule.EmbeddingIndexerSchedule$IndexerSchedule")))
        .containsExactly("indexerSchedule");
  }
}
