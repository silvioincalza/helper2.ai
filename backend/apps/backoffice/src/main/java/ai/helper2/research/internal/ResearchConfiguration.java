package ai.helper2.research.internal;

import ai.helper2.catalog.KnownTargets;
import ai.helper2.catalog.ReviewQueue;
import ai.helper2.platform.DailyJob;
import ai.helper2.platform.llm.CallCounter;
import ai.helper2.platform.llm.DailyBudget;
import ai.helper2.platform.llm.LlmSettings;
import ai.helper2.platform.llm.ModelCallMetrics;
import ai.helper2.platform.llm.UntrustedText;
import ai.helper2.platform.prompt.PromptTemplate;
import ai.helper2.platform.resilience.Resilience;
import ai.helper2.platform.settings.RuntimeSetting;
import ai.helper2.platform.settings.RuntimeSettings;
import ai.helper2.research.CheckResearch;
import ai.helper2.research.ResearchItems;
import ai.helper2.research.ResearchModel;
import ai.helper2.research.ResearchRuns;
import ai.helper2.research.ResearchSubject;
import ai.helper2.research.ResearchTool;
import ai.helper2.research.config.ResearchProperties;
import ai.helper2.research.usecase.CancelResearch;
import ai.helper2.research.usecase.FailStaleResearch;
import ai.helper2.research.usecase.FindResearchedItems;
import ai.helper2.research.usecase.PurgeResearch;
import ai.helper2.research.usecase.RunResearch;
import ai.helper2.research.usecase.StartResearch;
import ai.helper2.scout.EvidenceValidator;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.IntSupplier;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ResearchProperties.class)
class ResearchConfiguration {

  static final String PROMPT = "research/research.txt";

  @Bean
  ResearchRuns researchRuns(JdbcClient jdbc) {
    return new JdbcResearchRuns(jdbc, JsonMapper.builder().build());
  }

  @Bean
  ResearchItems researchItems(ReviewQueue queue, ResearchRuns runs) {
    return new ReviewQueueResearchItems(queue, runs, JsonMapper.builder().build());
  }

  @Bean
  CatalogLookupTool catalogLookupTool(ReviewQueue queue, KnownTargets targets) {
    return new CatalogLookupTool(queue, targets);
  }

  @Bean
  ResearchModel researchModel(
      ObjectProvider<ChatModel> chatModel,
      LlmSettings llm,
      ResearchProperties p,
      RuntimeSettings runtime,
      Clock clock,
      CallCounter counter,
      ModelCallMetrics metrics,
      Resilience resilience) {
    IntSupplier limit =
        runtime.declare(RuntimeSetting.REVIEW_RESEARCH_DAILY_MODEL_CALLS, p.dailyModelCalls());
    ChatModel model = chatModel.getIfAvailable();
    String name =
        p.model() == ResearchProperties.Model.FAST ? llm.fastModel() : llm.reasoningModel();
    if (!p.enabled() || model == null || !llm.usable(model) || name == null || name.isBlank()) {
      return new UnavailableResearchModel();
    }
    return new SpringAiResearchModel(
        model,
        name,
        p.maxTokens(),
        new DailyBudget(limit, clock, "review-research", counter),
        metrics,
        resilience,
        JsonMapper.builder().build());
  }

  @Bean
  RunResearch runResearch(
      ResearchRuns runs,
      ResearchItems items,
      ResearchModel model,
      List<ResearchTool> tools,
      Clock clock) {
    return new RunResearch(
        runs,
        items,
        model,
        new ArrayList<>(tools),
        new CheckResearch(),
        prompt(),
        ResearchConfiguration::subjectText,
        clock);
  }

  @Bean
  StartResearch startResearch(ResearchRuns runs, ResearchItems items, ResearchModel model) {
    return new StartResearch(runs, items, model.model());
  }

  @Bean
  FindResearchedItems findResearchedItems(ResearchRuns runs) {
    return new FindResearchedItems(runs);
  }

  @Bean
  CancelResearch cancelResearch(ResearchRuns runs, Clock clock) {
    return new CancelResearch(runs, clock);
  }

  @Bean
  PurgeResearch purgeResearch(ResearchRuns runs, Clock clock) {
    return new PurgeResearch(runs, clock);
  }

  @Bean
  FailStaleResearch failStaleResearch(ResearchRuns runs, Clock clock) {
    return new FailStaleResearch(runs, clock);
  }

  @Bean
  DailyJob researchRetention(PurgeResearch purge) {
    return new DailyJob("review-research", purge::purge);
  }

  @Bean
  ResearchWorkers researchWorkers(
      RunResearch research, FailStaleResearch stale, ResearchProperties p) {
    return new ResearchWorkers(research, stale, () -> p.enabled() ? p.concurrency() : 0, p.tick());
  }

  static String prompt() {
    return PromptTemplate.fromClasspath(PROMPT)
        .render(
            Map.of(
                "minExcerptChars",
                EvidenceValidator.MIN_EXCERPT_CHARS,
                "maxExcerptChars",
                EvidenceValidator.MAX_EXCERPT_CHARS,
                "minTagChars",
                CheckResearch.MIN_TAG_CHARS,
                "maxTagChars",
                CheckResearch.MAX_TAG_CHARS))
        .strip();
  }

  static String subjectText(ResearchSubject s) {
    StringBuilder out = new StringBuilder();
    out.append("Review item: ").append(s.kind()).append('\n');
    out.append("URL: ").append(UntrustedText.untrusted(s.url(), 2000)).append('\n');
    out.append("Title as found: ").append(UntrustedText.untrusted(s.title(), 300)).append('\n');
    out.append("Type as found: ").append(s.type()).append('\n');
    if (!s.tags().isEmpty()) {
      out.append("Tags as found: ")
          .append(UntrustedText.untrusted(String.join(", ", s.tags()), 500))
          .append('\n');
    }
    if (s.snippet() != null) {
      out.append("Search excerpt: ")
          .append(UntrustedText.untrusted(s.snippet(), 1500))
          .append('\n');
    }
    return out.toString().strip();
  }
}
