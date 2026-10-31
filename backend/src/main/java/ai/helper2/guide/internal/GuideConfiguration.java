package ai.helper2.guide.internal;

import ai.helper2.catalog.Embedder;
import ai.helper2.guide.AnswerLimits;
import ai.helper2.guide.ClientQuota;
import ai.helper2.guide.GuideAnswerCache;
import ai.helper2.guide.GuideAnswerStore;
import ai.helper2.guide.GuideModel;
import ai.helper2.guide.SimilarQuestions;
import ai.helper2.guide.usecase.AskGuide;
import ai.helper2.guide.GuideValidator;
import ai.helper2.platform.llm.CallCounter;
import ai.helper2.platform.llm.DailyBudget;
import ai.helper2.platform.llm.LlmSettings;
import ai.helper2.platform.llm.ModelCallMetrics;
import ai.helper2.platform.resilience.Resilience;
import ai.helper2.search.usecase.SearchResources;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
class GuideConfiguration {

    private static final Logger log = LoggerFactory.getLogger(GuideConfiguration.class);

    /** Guide answers survive restarts and deploys in Redis; without Redis the cache is in memory only. */
    @Bean
    GuideAnswerStore guideAnswerStore(ObjectProvider<StringRedisTemplate> redis, ObjectMapper json, Resilience resilience) {
        StringRedisTemplate template = redis.getIfAvailable();
        if (template == null) {
            return GuideAnswerStore.NONE;
        }
        return new RedisGuideAnswerStore(template, json, resilience);
    }

    /** Daily model-call cap of the guide (ADR 0017); every provider attempt takes one call. */
    @Bean
    DailyBudget guideBudget(Clock clock, CallCounter budgetCounter, MeterRegistry meters,
                            @Value("${helper2.guide.daily-model-calls:600}") int dailyModelCalls) {
        DailyBudget budget = new DailyBudget(dailyModelCalls, clock, "guide", budgetCounter);
        Gauge.builder("helper2.guide.budget.remaining", budget, DailyBudget::remaining)
            .description("Guide model calls left under today's cap (UTC day)")
            .register(meters);
        return budget;
    }

    /**
     * Reuse of a similar question's answer (ADR 0041): on only with a threshold, the question embedder of vector search
     * (ADR 0012) and Redis for the index. Unset threshold: off, no question is embedded.
     */
    static SimilarQuestions similarQuestions(String threshold, Embedder embedder, StringRedisTemplate redis, Resilience resilience,
                                             Clock clock) {
        if (threshold == null || threshold.isBlank()) {
            return null;
        }
        if (embedder == null || redis == null) {
            log.warn("H2_GUIDE_SIMILAR_THRESHOLD is set but {} is missing: similar questions are not reused",
                embedder == null ? "the embedding API key" : "Redis");
            return null;
        }
        double value = Double.parseDouble(threshold.strip());
        log.info("AI guide reuses the answer of a similar question: cosine at least {}, embeddings by {}", value, embedder.model());
        return new SimilarQuestions(value, embedder.model(),
            question -> embedder.embed(List.of(question), Embedder.Kind.QUERY).getFirst(),
            new RedisSimilarQuestionIndex(redis, resilience, clock, RedisSimilarQuestionIndex.DEFAULT_MAX_PER_GROUP), clock);
    }

    @Bean
    AskGuide guideService(SearchResources search, ObjectProvider<ChatModel> chatModel, LlmSettings llm, Clock clock,
                              DailyBudget guideBudget, CallCounter budgetCounter, GuideAnswerStore answerStore,
                              ObjectProvider<Embedder> embedder, ObjectProvider<StringRedisTemplate> redis,
                              ModelCallMetrics metrics, ObservationRegistry observations, MeterRegistry meters, Resilience resilience,
                              @Value("${helper2.llm.native-schema:true}") boolean nativeSchema,
                              @Value("${helper2.llm.choose-max-tokens:" + SpringAiGuideModel.DEFAULT_CHOOSE_MAX_TOKENS + "}") int chooseMaxTokens,
                              @Value("${helper2.guide.cache-ttl:PT24H}") Duration cacheTtl,
                              @Value("${helper2.guide.cache-max-entries:500}") int cacheMaxEntries,
                              @Value("${helper2.guide.similar-threshold:}") String similarThreshold,
                              @Value("${helper2.guide.max-candidates:24}") int maxCandidates,
                              @Value("${helper2.guide.client-daily-questions:30}") int clientDailyQuestions,
                              @Value("${helper2.guide.thinking.plan:false}") boolean thinkPlan,
                              @Value("${helper2.guide.thinking.choose:false}") boolean thinkChoose,
                              @Value("${helper2.guide.thinking.verify:false}") boolean thinkVerify) {
        ChatModel model = chatModel.getIfAvailable();
        GuideModel guideModel = null;
        if (llm.usable(model) && llm.hasReasoningModel()) {
            guideModel = new SpringAiGuideModel(model, llm.provider(), llm.fast(), llm.reasoningModel(), nativeSchema, chooseMaxTokens,
                new SpringAiGuideModel.Thinking(thinkPlan, thinkChoose, thinkVerify), guideBudget, metrics, resilience);
            log.info("AI guide enabled: provider={}, fast={}, reasoning={}, nativeSchemaForChoose={}, chooseMaxTokens={}, "
                    + "thinking plan/choose/verify={}/{}/{}",
                llm.provider(), llm.fast(), llm.reasoningModel(), nativeSchema, chooseMaxTokens, thinkPlan, thinkChoose, thinkVerify);
        } else {
            log.info("AI guide without a model: answers degrade to top search results (LLM_UNAVAILABLE)");
        }
        log.info("AI guide: Nemotron chooses among up to {} candidates; {} fresh questions per client per day",
            maxCandidates, clientDailyQuestions);
        return new AskGuide(search, guideModel, clock, maxCandidates, new GuideValidator(),
            new GuideAnswerCache(cacheTtl, cacheMaxEntries, clock, answerStore), observations, meters,
            new ClientQuota(clientDailyQuestions, clock, budgetCounter), AskGuide.SUBJECT_SEARCH_DEADLINE, AnswerLimits.DEFAULT,
            similarQuestions(similarThreshold, embedder.getIfAvailable(), redis.getIfAvailable(), resilience, clock));
    }
}
