package ai.helper2.examples.internal;

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
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The model that drafts home examples, with its own daily budget (ADR 0049 §6). */
@Configuration(proxyBeanMethods = false)
class ExamplesConfiguration {

  private static final Logger log = LoggerFactory.getLogger(ExamplesConfiguration.class);

  /** The drafts' own daily model-call cap, apart from the guide's, the Scout's and the setup's. */
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

  /** Without a model: every draft run answers that drafts are off. */
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
