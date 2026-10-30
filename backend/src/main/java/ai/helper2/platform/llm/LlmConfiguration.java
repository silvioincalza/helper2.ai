package ai.helper2.platform.llm;

import ai.helper2.platform.resilience.Resilience;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration(proxyBeanMethods = false)
class LlmConfiguration {

    /** Every module's model settings; startup stops here when a configured model is not an NVIDIA one (NvidiaOnly). */
    @Bean
    LlmSettings llmSettings(@Value("${spring.ai.model.chat:none}") String chatProvider,
                            @Value("${helper2.llm.provider-label:}") String providerLabel,
                            @Value("${helper2.llm.fast-model:}") String fastModel,
                            @Value("${helper2.llm.reasoning-model:}") String reasoningModel,
                            @Value("${spring.ai.openai.chat.model:}") String chatModel,
                            @Value("${helper2.embedding.api-key:}") String embeddingKey,
                            @Value("${helper2.embedding.model:}") String embeddingModel) {
        NvidiaOnly.require(chatProvider, chatModel, fastModel, reasoningModel, embeddingKey, embeddingModel);
        return new LlmSettings(chatProvider, providerLabel, fastModel, reasoningModel);
    }

    /** Daily budgets survive restarts in Redis; without Redis they count in memory. */
    @Bean
    CallCounter budgetCounter(ObjectProvider<StringRedisTemplate> redis, Resilience resilience) {
        StringRedisTemplate template = redis.getIfAvailable();
        return template == null ? CallCounter.NONE : new RedisCallCounter(template, resilience);
    }

    /** One metrics recorder for every model call, whichever module makes it. */
    @Bean
    ModelCallMetrics modelCallMetrics(ObservationRegistry observations, MeterRegistry meters) {
        return new ModelCallMetrics(observations, meters);
    }
}
