package ai.helper2.guide.internal;

import ai.helper2.catalog.Embedder;
import ai.helper2.guide.AnswerLimits;
import ai.helper2.guide.ClientQuota;
import ai.helper2.guide.GuideAnswerCache;
import ai.helper2.guide.GuideAnswerStore;
import ai.helper2.guide.GuideModel;
import ai.helper2.guide.GuidePolicy;
import ai.helper2.guide.GuideValidator;
import ai.helper2.guide.SimilarQuestionIndex;
import ai.helper2.guide.SimilarQuestions;
import ai.helper2.guide.config.GuideProperties;
import ai.helper2.guide.usecase.AskGuide;
import ai.helper2.platform.cache.SharedCaches;
import ai.helper2.platform.config.BuildInfoProperties;
import ai.helper2.platform.config.LlmProperties;
import ai.helper2.platform.llm.CallCounter;
import ai.helper2.platform.llm.DailyBudget;
import ai.helper2.platform.llm.LlmSettings;
import ai.helper2.platform.llm.ModelCallMetrics;
import ai.helper2.platform.quota.QuotaKeys;
import ai.helper2.platform.resilience.Resilience;
import ai.helper2.platform.settings.RuntimeSetting;
import ai.helper2.platform.settings.RuntimeSettings;
import ai.helper2.platform.settings.Setting;
import ai.helper2.platform.settings.SettingsGroup;
import ai.helper2.search.usecase.SearchResources;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
class GuideConfiguration {

  private static final Logger log = LoggerFactory.getLogger(GuideConfiguration.class);

  /** guide_similar_question.embedding is vector(1024), like resource_embedding. */
  static final int INDEX_DIMENSIONS = 1024;

  /**
   * Guide answers in the shared cache (ADR 0041): they survive restarts and serve every replica,
   * per build.
   */
  @Bean
  SharedGuideAnswerStore guideAnswerStore(
      SharedCaches caches, ObjectMapper json, BuildInfoProperties build) {
    return new SharedGuideAnswerStore(caches.named("guide-answer"), json, build.version());
  }

  /**
   * The similar-question index; also purged while reuse is off, so rows of an earlier setting go
   * too.
   */
  @Bean
  JdbcSimilarQuestionIndex similarQuestionIndex(JdbcClient jdbc) {
    return new JdbcSimilarQuestionIndex(jdbc);
  }

  /**
   * Daily model-call cap of the guide (ADR 0017); every provider attempt takes one call. A runtime
   * setting (ADR 0060): the H2_ value is its default.
   */
  @Bean
  DailyBudget guideBudget(
      Clock clock,
      CallCounter budgetCounter,
      MeterRegistry meters,
      RuntimeSettings runtime,
      GuideProperties settings) {
    DailyBudget budget =
        new DailyBudget(
            runtime.declare(RuntimeSetting.GUIDE_DAILY_MODEL_CALLS, settings.dailyModelCalls()),
            clock,
            "guide",
            budgetCounter);
    Gauge.builder("helper2.guide.budget.remaining", budget, DailyBudget::remaining)
        .description("Guide model calls left under today's cap (UTC day)")
        .register(meters);
    return budget;
  }

  /**
   * Reuse of a similar question's answer (ADR 0041): on only with a threshold, the question
   * embedder of vector search (ADR 0012), whose vectors fit the index's column. Unset threshold:
   * off, no question is embedded.
   */
  static SimilarQuestions similarQuestions(
      Double threshold, Embedder embedder, SimilarQuestionIndex index, Clock clock) {
    if (threshold == null) {
      return null;
    }
    if (embedder == null || embedder.dimensions() != INDEX_DIMENSIONS) {
      log.warn(
          "H2_GUIDE_SIMILAR_THRESHOLD is set but {}: similar questions are not reused",
          embedder == null
              ? "the embedding API key is missing"
              : "the embeddings are not " + INDEX_DIMENSIONS + " long");
      return null;
    }
    double value = threshold;
    log.info(
        "AI guide reuses the answer of a similar question: cosine at least {}, embeddings by {}",
        value,
        embedder.model());
    return new SimilarQuestions(
        value,
        embedder.model(),
        question -> embedder.embed(List.of(question), Embedder.Kind.QUERY).getFirst(),
        index,
        clock);
  }

  /** The guide's numbers for GET /api/v1/policy (ADR 0052). */
  @Bean
  GuidePolicy guidePolicy(GuideProperties settings) {
    return new GuidePolicy(
        settings.clientDailyQuestions(), settings.maxCandidates(), settings.cacheTtl());
  }

  /**
   * Fresh guide questions per client and day, and how many answers with no pick a day give theirs
   * back (ADR 0017); also read by GET /me.
   */
  @Bean
  ClientQuota guideClientQuota(
      Clock clock, CallCounter budgetCounter, QuotaKeys quotaKeys, GuideProperties settings) {
    return new ClientQuota(
        settings.clientDailyQuestions(),
        clock,
        quotaKeys,
        budgetCounter,
        ClientQuota.MAX_CLIENTS,
        settings.clientDailyFreeAnswers());
  }

  @Bean
  AskGuide guideService(
      SearchResources search,
      ObjectProvider<ChatModel> chatModel,
      LlmSettings llm,
      Clock clock,
      DailyBudget guideBudget,
      GuideAnswerStore answerStore,
      ObjectProvider<Embedder> embedder,
      JdbcSimilarQuestionIndex similarQuestionIndex,
      ModelCallMetrics metrics,
      ObservationRegistry observations,
      MeterRegistry meters,
      Resilience resilience,
      ClientQuota clientQuota,
      LlmProperties llmProperties,
      GuideProperties settings) {
    boolean nativeSchema = llmProperties.nativeSchema();
    int chooseMaxTokens = settings.chooseMaxTokens();
    int maxCandidates = settings.maxCandidates();
    boolean thinkPlan = settings.thinking().plan();
    boolean thinkChoose = settings.thinking().choose();
    boolean thinkVerify = settings.thinking().verify();
    ChatModel model = chatModel.getIfAvailable();
    GuideModel guideModel = null;
    if (llm.usable(model) && llm.hasReasoningModel()) {
      guideModel =
          new SpringAiGuideModel(
              model,
              llm.provider(),
              llm.fast(),
              llm.reasoningModel(),
              nativeSchema,
              chooseMaxTokens,
              new SpringAiGuideModel.Thinking(thinkPlan, thinkChoose, thinkVerify),
              guideBudget,
              metrics,
              resilience);
      log.info(
          "AI guide enabled: provider={}, fast={}, reasoning={}, nativeSchemaForChoose={}, chooseMaxTokens={}, "
              + "thinking plan/choose/verify={}/{}/{}",
          llm.provider(),
          llm.fast(),
          llm.reasoningModel(),
          nativeSchema,
          chooseMaxTokens,
          thinkPlan,
          thinkChoose,
          thinkVerify);
    } else {
      log.info("AI guide without a model: answers degrade to top search results (LLM_UNAVAILABLE)");
    }
    log.info(
        "AI guide: Nemotron chooses among up to {} candidates; {} fresh questions per client per day",
        maxCandidates,
        clientQuota.getLimit());
    return new AskGuide(
        search,
        guideModel,
        clock,
        maxCandidates,
        new GuideValidator(),
        new GuideAnswerCache(settings.cacheTtl(), clock, answerStore),
        observations,
        meters,
        clientQuota,
        AskGuide.SUBJECT_SEARCH_DEADLINE,
        AnswerLimits.DEFAULT,
        similarQuestions(
            settings.similarThreshold(), embedder.getIfAvailable(), similarQuestionIndex, clock));
  }

  /** The guide's settings for the back office's Settings page (ADR 0045). */
  @Bean
  SettingsGroup guideSettings(GuideProperties settings, LlmSettings llm, AskGuide guideService) {
    return guideSettingsGroup(
        settings, llm, guideService.getAnswersWithModel(), guideService.getConfigHash());
  }

  static SettingsGroup guideSettingsGroup(
      GuideProperties settings, LlmSettings llm, boolean withModel, String configHash) {
    return new SettingsGroup(
        "guide",
        "AI guide",
        SettingsGroup.GUIDE,
        List.of(
            Setting.count(
                "guide.dailyModelCalls",
                "Model calls a day, all visitors",
                settings.dailyModelCalls(),
                "calls a day",
                "H2_GUIDE_DAILY_MODEL_CALLS"),
            Setting.count(
                "guide.clientDailyQuestions",
                "Questions a day per visitor",
                settings.clientDailyQuestions(),
                "questions a day",
                "H2_GUIDE_CLIENT_DAILY_QUESTIONS"),
            Setting.count(
                "guide.clientDailyFreeAnswers",
                "Answers with no pick given back a day",
                settings.clientDailyFreeAnswers(),
                "answers a day",
                "H2_GUIDE_CLIENT_DAILY_FREE_ANSWERS"),
            Setting.count(
                "guide.maxCandidates",
                "Results the model chooses from",
                settings.maxCandidates(),
                "results",
                "H2_GUIDE_MAX_CANDIDATES"),
            Setting.count(
                "guide.chooseMaxTokens",
                "Output cap of the choose call",
                settings.chooseMaxTokens(),
                "tokens",
                "H2_NEBIUS_CHOOSE_MAX_TOKENS"),
            Setting.model("guide.planModel", "Plan model", withModel ? llm.fast() : null, null),
            Setting.model(
                "guide.chooseModel", "Choose model", withModel ? llm.reasoningModel() : null, null),
            Setting.version("guide.configHash", "Configuration hash", configHash)));
  }
}
