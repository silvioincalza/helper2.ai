package ai.helper2.sources.config.scout;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.TestcontainersConfiguration;
import ai.helper2.scout.Collector;
import ai.helper2.sources.ScoutSourceIds;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class CollectorsParityIT {

  @Autowired List<Collector> collectors;

  @Autowired Environment env;

  @Test
  void everyCollectorIdCategoryAndFeedHasOneCollectorAndNoCollectorIsLeftOver() {
    assertThat(collectors)
        .extracting(c -> c.getDescriptor().getId())
        .containsExactlyInAnyOrderElementsOf(ScoutSourceIds.of(env));
  }
}
