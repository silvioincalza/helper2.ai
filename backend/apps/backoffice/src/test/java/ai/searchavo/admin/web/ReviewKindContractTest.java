package ai.searchavo.admin.web;

import static org.assertj.core.api.Assertions.assertThat;

import ai.searchavo.api.generated.model.ReviewKindDto;
import ai.searchavo.catalog.ReviewQueue;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class ReviewKindContractTest {

  @Test
  void contractAndDomainKindsMatch() {
    assertThat(Arrays.stream(ReviewKindDto.values()).map(ReviewKindDto::getValue).toList())
        .containsExactlyInAnyOrderElementsOf(
            Arrays.stream(ReviewQueue.Kind.values()).map(Enum::name).toList());
  }
}
