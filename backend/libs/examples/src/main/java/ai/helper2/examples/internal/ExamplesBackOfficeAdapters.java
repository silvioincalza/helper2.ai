package ai.helper2.examples.internal;

import ai.helper2.catalog.ReviewQueue;
import ai.helper2.catalog.usecase.ListWellKnownResources;
import ai.helper2.catalog.usecase.LookUpResourceTitles;
import ai.helper2.examples.DraftSource;
import ai.helper2.examples.DraftsUnavailable;
import ai.helper2.examples.ExampleDrafter;
import ai.helper2.examples.ProposedDraft;
import ai.helper2.examples.config.ExamplesProperties;
import ai.helper2.platform.llm.CallCounter;
import ai.helper2.platform.llm.DailyBudget;
import ai.helper2.platform.llm.LlmSettings;
import ai.helper2.platform.llm.ModelCallMetrics;
import ai.helper2.platform.resilience.Resilience;
import ai.helper2.platform.settings.Setting;
import ai.helper2.platform.settings.SettingsGroup;
import ai.helper2.search.usecase.SearchResources;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

public class ExamplesBackOfficeAdapters {

  private static final Logger log = LoggerFactory.getLogger(ExamplesBackOfficeAdapters.class);

  @Bean
  JdbcHomeExamples jdbcHomeExamples(
      JdbcClient jdbc, Clock clock, PlatformTransactionManager transactions) {
    return new JdbcHomeExamples(jdbc, clock, transactions);
  }

  @Bean
  CatalogResourceTitles catalogResourceTitles(LookUpResourceTitles titles) {
    return new CatalogResourceTitles(titles);
  }

  @Bean
  CatalogDraftSources catalogDraftSources(ListWellKnownResources resources) {
    return new CatalogDraftSources(resources);
  }

  @Bean
  ReviewQueueDrafts reviewQueueDrafts(ReviewQueue queue, ObjectMapper json) {
    return new ReviewQueueDrafts(queue, json);
  }

  @Bean
  SearchExampleSearch searchExampleSearch(SearchResources search) {
    return new SearchExampleSearch(search);
  }

  @Bean
  SettingsGroup exampleSettings(
      ExamplesProperties examples, LlmSettings llm, ObjectProvider<ChatModel> chatModel) {
    boolean on = llm.usable(chatModel.getIfAvailable()) && examples.draftCalls() > 0;
    return new SettingsGroup(
        "examples",
        "Home examples",
        SettingsGroup.EXAMPLES,
        List.of(
            Setting.count(
                "examples.draftCalls",
                "Draft runs a day",
                examples.draftCalls(),
                "calls a day",
                "H2_EXAMPLES_DRAFT_CALLS"),
            Setting.model("examples.draftModel", "Draft model", on ? llm.fast() : null, null)));
  }

  @Bean
  DailyBudget exampleDraftBudget(
      Clock clock, CallCounter budgetCounter, MeterRegistry meters, ExamplesProperties examples) {
    DailyBudget budget =
        new DailyBudget(examples.draftCalls(), clock, "example-drafts", budgetCounter);
    Gauge.builder("helper2.examples.draft_budget.remaining", budget, DailyBudget::remaining)
        .description("Example draft model calls left under today's cap (UTC day)")
        .register(meters);
    return budget;
  }

  @Bean
  ExampleDrafter exampleDrafter(
      ObjectProvider<ChatModel> chatModel,
      LlmSettings llm,
      DailyBudget exampleDraftBudget,
      ModelCallMetrics metrics,
      Resilience resilience,
      ExamplesProperties examples) {
    ChatModel model = chatModel.getIfAvailable();
    if (!llm.usable(model) || examples.draftCalls() == 0) {
      log.info("Example drafts off: no model, or H2_EXAMPLES_DRAFT_CALLS is 0");
      return new NoDrafter();
    }
    log.info("Example drafts enabled: model={}", llm.fast());
    return new SpringAiExampleDrafter(model, llm.fast(), exampleDraftBudget, metrics, resilience);
  }

  static final class NoDrafter implements ExampleDrafter {
    @Override
    public String getModel() {
      return "";
    }

    @Override
    public List<ProposedDraft> draft(List<DraftSource> sources) {
      throw new DraftsUnavailable(DraftsUnavailable.Reason.NO_MODEL);
    }
  }
}
