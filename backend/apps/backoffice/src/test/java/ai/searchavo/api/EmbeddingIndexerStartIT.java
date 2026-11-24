package ai.searchavo.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(
    properties = {
      "searchavo.embedding.api-key=test-key",
      "searchavo.catalog.indexer.initial-delay=PT24H"
    })
class EmbeddingIndexerStartIT extends ApiTestBase {

  @Autowired ApplicationContext context;

  @Test
  void theBackOfficeStartsWithTheIndexerScheduleWhenAnEmbeddingKeyIsSet()
      throws ClassNotFoundException {
    assertThat(
            context.getBeanNamesForType(
                Class.forName(
                    "ai.searchavo.catalog.schedule.EmbeddingIndexerSchedule$IndexerSchedule")))
        .containsExactly("indexerSchedule");
  }
}
