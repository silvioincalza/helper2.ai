package ai.searchavo.sources.config.scout;

import static org.assertj.core.api.Assertions.assertThat;

import ai.searchavo.platform.llm.LlmSettings;
import ai.searchavo.search.DescribedSource;
import ai.searchavo.sources.ScoutSourceIds;
import ai.searchavo.sources.config.SourcesProperties;
import ai.searchavo.testing.ConfigBinding;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

class ScoutSourceDescriptorsParityTest {

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(SourcesProperties.class)
  @Import(ScoutSourceDescriptors.class)
  static class Descriptors {
    @Bean
    LlmSettings llmSettings() {
      return new LlmSettings("none", "", "", "");
    }
  }

  final ApplicationContextRunner context = ConfigBinding.of(Descriptors.class);

  @Test
  void everyCollectorIdCategoryAndFeedHasOneDescriptorAndNoDescriptorIsLeftOver() {
    context.run(c -> {
      assertThat(c).hasNotFailed();
      Map<String, DescribedSource> descriptors = c.getBeansOfType(DescribedSource.class);
      assertThat(descriptors.values())
          .extracting(d -> d.getDescriptor().getId())
          .containsExactlyInAnyOrderElementsOf(ScoutSourceIds.of(c.getEnvironment()));
    });
  }
}
