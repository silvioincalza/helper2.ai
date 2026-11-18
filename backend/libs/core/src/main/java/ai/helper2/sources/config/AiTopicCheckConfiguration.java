package ai.helper2.sources.config;

import ai.helper2.search.AiTopicCheck;
import ai.helper2.sources.support.Classification;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class AiTopicCheckConfiguration {

  @Bean
  AiTopicCheck aiTopicCheck() {
    return Classification::isAiRelated;
  }
}
