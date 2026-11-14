package ai.helper2.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

class RateLimitedOperationsTest {

  static final JsonNode CONTRACT =
      YAMLMapper.builder().build().readTree(new File(System.getProperty("openapi.spec")));

  @Test
  void everyOperationWithItsOwnLimitIsAnOperationOfTheContract() {
    Set<String> operations = Set.copyOf(CONTRACT.findValuesAsString("operationId"));

    assertThat(operations).isNotEmpty().containsAll(RateLimitInterceptor.OWN_LIMIT.keySet());
    assertThat(RateLimitInterceptor.LIMITED_OUTSIDE_THE_API)
        .doesNotContainAnyElementsOf(operations);
  }
}
