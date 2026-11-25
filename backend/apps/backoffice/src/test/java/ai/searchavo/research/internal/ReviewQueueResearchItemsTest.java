package ai.searchavo.research.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ai.searchavo.catalog.ReviewQueue;
import ai.searchavo.research.ResearchSubject;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ReviewQueueResearchItemsTest {

  final ReviewQueueResearchItems items =
      new ReviewQueueResearchItems(null, null, JsonMapper.builder().build());

  @Test
  void theSubjectCarriesTheConfirmedValuesOfTheCandidate() {
    ResearchSubject subject =
        items.subject(
            item(
                """
                {"url":"https://acme.example/","title":"Acme","type":"PRODUCT","tags":["agents"],
                 "confirmed":{"license":"MIT","openSource":"true","pricing":"free"}}
                """));

    assertThat(subject.confirmed())
        .isEqualTo(Map.of("license", "MIT", "openSource", "true", "pricing", "free"));
    assertThat(subject.tags()).containsExactly("agents");
  }

  @Test
  void aCandidateWithoutConfirmedValuesHasNone() {
    assertThat(
            items
                .subject(item("{\"url\":\"https://acme.example/\",\"title\":\"Acme\"}"))
                .confirmed())
        .isEmpty();
  }

  private static ReviewQueue.Item item(String details) {
    return new ReviewQueue.Item(
        UUID.randomUUID(),
        ReviewQueue.Kind.RESOURCE_CANDIDATE,
        ReviewQueue.Status.OPEN,
        null,
        null,
        "New PRODUCT found by github: Acme",
        details,
        Instant.EPOCH,
        null,
        null,
        List.of());
  }
}
