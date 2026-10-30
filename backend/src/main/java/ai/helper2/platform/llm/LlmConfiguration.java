package ai.helper2.platform.llm;

import ai.helper2.platform.resilience.Resilience;
import ai.helper2.search.QueryTranslator;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration(proxyBeanMethods = false)
class LlmConfiguration {

    private static final Logger log = LoggerFactory.getLogger(LlmConfiguration.class);

    @Bean
    LlmSettings llmSettings(@Value("${spring.ai.model.chat:none}") String chatProvider,
                            @Value("${helper2.llm.provider-label:}") String providerLabel,
                            @Value("${helper2.llm.fast-model:}") String fastModel,
                            @Value("${helper2.llm.reasoning-model:}") String reasoningModel) {
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

    /**
     * Non-English search queries in English words, with the fast model; off without a configured model.
     * Its own daily cap, separate from the guide's: search allows far more requests per client than the
     * guide, so a flood of unique foreign-language queries must not spend the guide's budget.
     */
    @Bean
    QueryTranslator queryTranslator(ObjectProvider<ChatModel> chatModel, LlmSettings llm, Clock clock,
                                    CallCounter budgetCounter, ModelCallMetrics metrics, MeterRegistry meters, Resilience resilience,
                                    @Value("${helper2.search.daily-translations:300}") int dailyTranslations,
                                    @Value("${helper2.search.translate-queries:true}") boolean enabled) {
        ChatModel model = chatModel.getIfAvailable();
        if (!enabled || !llm.usable(model) || !llm.hasReasoningModel()) {
            log.info("Search query translation off");
            return QueryTranslator.NONE;
        }
        log.info("Search query translation on: model={}, dailyTranslations={}", llm.fast(), dailyTranslations);
        DailyBudget budget = new DailyBudget(dailyTranslations, clock, "translation", budgetCounter);
        Gauge.builder("helper2.search.translation.budget.remaining", budget, DailyBudget::remaining)
            .description("Search query translations left under today's cap (UTC day)")
            .register(meters);
        return new SpringAiQueryTranslator(model, llm.fast(), budget, clock, Duration.ofHours(24), 2000, metrics, resilience);
    }
}
