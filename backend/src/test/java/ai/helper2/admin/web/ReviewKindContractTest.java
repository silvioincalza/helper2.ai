package ai.helper2.admin.web;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.api.generated.model.ReviewKindDto;
import ai.helper2.catalog.ReviewQueue;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * The contract's review kinds and the queue's are the same set, so a mapping never fails with a
 * 500.
 */
class ReviewKindContractTest {

  @Test
  void contractAndDomainKindsMatch() {
    assertThat(Arrays.stream(ReviewKindDto.values()).map(ReviewKindDto::getValue).toList())
        .containsExactlyInAnyOrderElementsOf(
            Arrays.stream(ReviewQueue.Kind.values()).map(Enum::name).toList());
  }
}
