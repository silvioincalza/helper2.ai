package ai.helper2.examples.config;

import ai.helper2.examples.ServedExamples;
import ai.helper2.examples.usecase.ListHomeExamples;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class ExamplesUseCaseConfiguration {

  @Bean
  ListHomeExamples listHomeExamples(ServedExamples examples) {
    return new ListHomeExamples(examples);
  }
}
