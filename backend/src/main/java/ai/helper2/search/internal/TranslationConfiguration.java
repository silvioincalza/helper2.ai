package ai.helper2.search.internal;

import ai.helper2.platform.cache.SharedCaches;
import ai.helper2.platform.llm.CallCounter;
import ai.helper2.platform.llm.DailyBudget;
import ai.helper2.platform.llm.LlmSettings;
import ai.helper2.platform.llm.ModelCallMetrics;
import ai.helper2.platform.resilience.Resilience;
import ai.helper2.search.QueryTranslator;
import ai.helper2.search.config.SearchProperties;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The query translation of search (ADR 0011): an adapter of the search module's QueryTranslator
 * port.
 */
@Configuration(proxyBeanMethods = false)
class TranslationConfiguration {

  private static final Logger log = LoggerFactory.getLogger(TranslationConfiguration.class);

  /**
   * Non-English search queries in English words, with the fast model; off without a configured
   * model. Its own daily cap, separate from the guide's: search allows far more requests per client
   * than the guide, so a flood of unique foreign-language queries must not spend the guide's
   * budget. Translations are shared by the replicas for {@code translationTtl}.
   */
  @Bean
  QueryTranslator queryTranslator(
      ObjectProvider<ChatModel> chatModel,
      LlmSettings llm,
      Clock clock,
      CallCounter budgetCounter,
      ModelCallMetrics metrics,
      MeterRegistry meters,
      Resilience resilience,
      SharedCaches caches,
      SearchProperties search) {
    SearchProperties.Translation settings = search.translation();
    int dailyTranslations = settings.dailyCalls();
    ChatModel model = chatModel.getIfAvailable();
    if (!settings.enabled() || !llm.usable(model) || !llm.hasReasoningModel()) {
      log.info("Search query translation off");
      return QueryTranslator.NONE;
    }
    log.info(
        "Search query translation on: model={}, dailyTranslations={}",
        llm.fast(),
        dailyTranslations);
    DailyBudget budget = new DailyBudget(dailyTranslations, clock, "translation", budgetCounter);
    Gauge.builder("helper2.search.translation.budget.remaining", budget, DailyBudget::remaining)
        .description("Search query translations left under today's cap (UTC day)")
        .register(meters);
    return new SpringAiQueryTranslator(
        model,
        llm.fast(),
        budget,
        caches.named("translation"),
        settings.ttl(),
        metrics,
        resilience);
  }
}
