package ai.searchavo.sources.config;

import ai.searchavo.search.AiTopicCheck;
import ai.searchavo.sources.support.Classification;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class AiTopicCheckConfiguration {

  @Bean
  AiTopicCheck aiTopicCheck() {
    return Classification::isAiRelated;
  }
}
