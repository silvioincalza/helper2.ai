package ai.helper2.platform.config;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.testing.ConfigBinding;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

class HttpClientPropertiesBindingTest {

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(HttpClientProperties.class)
  static class Binding {}

  final ApplicationContextRunner context = ConfigBinding.of(Binding.class);

  @Test
  void theYamlValuesBind() {
    context.run(c ->
        assertThat(c.getBean(HttpClientProperties.class))
            .isEqualTo(
                new HttpClientProperties(Duration.ofMillis(250), 50, 20, DataSize.ofMegabytes(2))));
  }

  @Test
  void moreConnectionsToOneHostThanThePoolHoldsStopStartup() {
    context
        .withPropertyValues("helper2.http.max-connections-per-route=51")
        .run(c -> assertThat(c).hasFailed());
  }

  @Test
  void aPoolWaitOfZeroStopsStartup() {
    context.withPropertyValues("helper2.http.pool-wait=0ms").run(c -> assertThat(c).hasFailed());
  }

  @Test
  void anEmptyResponseCapStopsStartup() {
    context
        .withPropertyValues("helper2.http.max-response-size=0B")
        .run(c -> assertThat(c).hasFailed());
  }
}
