package ai.helper2.search.internal;

import ai.helper2.platform.cache.SharedCaches;
import ai.helper2.platform.llm.CallCounter;
import ai.helper2.platform.llm.DailyBudget;
import ai.helper2.platform.llm.LlmSettings;
import ai.helper2.platform.llm.ModelCallMetrics;
import ai.helper2.platform.resilience.Resilience;
import ai.helper2.platform.settings.RuntimeSetting;
import ai.helper2.platform.settings.RuntimeSettings;
import ai.helper2.search.QueryTranslator;
import ai.helper2.search.config.SearchProperties;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.function.IntSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class TranslationConfiguration {

  private static final Logger log = LoggerFactory.getLogger(TranslationConfiguration.class);

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
      RuntimeSettings runtime,
      SearchProperties search) {
    SearchProperties.Translation settings = search.translation();
    int dailyTranslations = settings.dailyCalls();
    IntSupplier limit =
        runtime.declare(RuntimeSetting.SEARCH_DAILY_TRANSLATIONS, dailyTranslations);
    ChatModel model = chatModel.getIfAvailable();
    if (!llm.usable(model) || !llm.hasReasoningModel()) {
      log.info("Search query translation off");
      return QueryTranslator.NONE;
    }
    log.info(
        "Search query translation on: model={}, dailyTranslations={}",
        llm.fast(),
        dailyTranslations);
    DailyBudget budget = new DailyBudget(limit, clock, "translation", budgetCounter);
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
