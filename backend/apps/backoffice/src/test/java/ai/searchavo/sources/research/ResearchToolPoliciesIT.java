package ai.searchavo.sources.research;

import static org.assertj.core.api.Assertions.assertThat;

import ai.searchavo.TestcontainersConfiguration;
import ai.searchavo.platform.resilience.Resilience;
import ai.searchavo.testing.SourcesOff;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SourcesOff
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ResearchToolPoliciesIT {

  @Autowired Resilience resilience;

  @Autowired CircuitBreakerRegistry circuits;

  @Test
  void everyResearchToolPolicyIsInTheSettings() {
    for (String policy :
        List.of(WebSearchTool.POLICY, HuggingFaceTool.POLICY, GitHubRepoTool.POLICY)) {
      resilience.require(policy);
      assertThat(circuits.find(policy)).as(policy).isPresent();
    }
  }

  @Test
  void aSlowWebSearchIsNotAFailure() {
    var config = circuits.find(WebSearchTool.POLICY).orElseThrow().getCircuitBreakerConfig();
    assertThat(config.getSlowCallDurationThreshold()).isGreaterThan(Duration.ofSeconds(5));
  }
}
