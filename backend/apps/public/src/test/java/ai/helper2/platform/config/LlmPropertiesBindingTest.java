package ai.helper2.platform.config;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.testing.ConfigBinding;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class LlmPropertiesBindingTest {

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(LlmProperties.class)
  static class Binding {}

  final ApplicationContextRunner context = ConfigBinding.of(Binding.class);

  @Test
  void withoutAProfileThereIsNoModel() {
    context.run(c ->
        assertThat(c.getBean(LlmProperties.class))
            .isEqualTo(new LlmProperties("none", "", "", "", "", true)));
  }

  @Test
  void theNebiusProfileSetsTheProviderAndTheModels() {
    context
        .withPropertyValues("spring.profiles.active=llm-nebius")
        .run(c ->
            assertThat(c.getBean(LlmProperties.class))
                .isEqualTo(
                    new LlmProperties(
                        "openai",
                        "nvidia/Nemotron-3.5-Super-VL",
                        "nebius-token-factory",
                        "nvidia/Nemotron-3_5-Lightning",
                        "nvidia/Nemotron-3.5-Super-VL",
                        true)));
  }

  @Test
  void anH2VariableReachesItsFields() {
    context
        .withPropertyValues("spring.profiles.active=llm-nebius")
        .withSystemProperties(
            "H2_NEBIUS_REASONING_MODEL=nvidia/other", "H2_NEBIUS_NATIVE_SCHEMA=false")
        .run(c -> {
          LlmProperties llm = c.getBean(LlmProperties.class);
          assertThat(llm.reasoningModel()).isEqualTo("nvidia/other");
          assertThat(llm.chatModel()).as("one variable, two keys").isEqualTo("nvidia/other");
          assertThat(llm.nativeSchema()).isFalse();
        });
  }

  @Test
  void aBlankProviderStopsStartup() {
    context
        .withPropertyValues("spring.ai.model.chat=")
        .run(c -> {
          assertThat(c).hasFailed();
          assertThat(c.getStartupFailure())
              .rootCause()
              .isInstanceOf(BindValidationException.class)
              .hasMessageContaining("helper2.llm.chatProvider");
        });
  }
}
