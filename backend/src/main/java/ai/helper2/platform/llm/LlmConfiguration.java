package ai.helper2.platform.llm;

import ai.helper2.platform.config.EmbeddingProperties;
import ai.helper2.platform.config.LlmProperties;
import ai.helper2.platform.resilience.Resilience;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration(proxyBeanMethods = false)
class LlmConfiguration {

  /**
   * Every module's model settings; startup stops here when a configured model is not an NVIDIA one
   * (NvidiaOnly).
   */
  @Bean
  LlmSettings llmSettings(LlmProperties llm, EmbeddingProperties embedding) {
    NvidiaOnly.require(
        llm.chatProvider(),
        llm.chatModel(),
        llm.fastModel(),
        llm.reasoningModel(),
        embedding.apiKey(),
        embedding.model());
    return new LlmSettings(
        llm.chatProvider(), llm.providerLabel(), llm.fastModel(), llm.reasoningModel());
  }

  /**
   * Daily budgets survive restarts in Redis; without Redis they count in memory. The ledger is also
   * what the back office reads: every budget declares itself there.
   */
  @Bean
  BudgetLedger budgetCounter(
      ObjectProvider<StringRedisTemplate> redis, Resilience resilience, Clock clock) {
    StringRedisTemplate template = redis.getIfAvailable();
    return new BudgetLedger(
        template == null ? null : new RedisCallCounter(template, resilience), clock);
  }

  /** One metrics recorder for every model call, whichever module makes it. */
  @Bean
  ModelCallMetrics modelCallMetrics(ObservationRegistry observations, MeterRegistry meters) {
    return new ModelCallMetrics(observations, meters);
  }
}
