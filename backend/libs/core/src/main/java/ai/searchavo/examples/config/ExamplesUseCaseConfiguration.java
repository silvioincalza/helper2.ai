package ai.searchavo.examples.config;

import ai.searchavo.examples.ServedExamples;
import ai.searchavo.examples.usecase.ListHomeExamples;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class ExamplesUseCaseConfiguration {

  @Bean
  ListHomeExamples listHomeExamples(ServedExamples examples) {
    return new ListHomeExamples(examples);
  }
}
