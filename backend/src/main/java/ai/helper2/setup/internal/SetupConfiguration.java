package ai.helper2.setup.internal;

import ai.helper2.platform.http.ObservedHttp;
import ai.helper2.platform.llm.CallCounter;
import ai.helper2.platform.llm.DailyBudget;
import ai.helper2.platform.llm.LlmSettings;
import ai.helper2.platform.llm.ModelCallMetrics;
import ai.helper2.platform.resilience.Resilience;
import ai.helper2.setup.McpServerSpecs;
import ai.helper2.setup.ReadmeConfigs;
import ai.helper2.setup.SetupGuides;
import ai.helper2.setup.ToolGuideCache;
import ai.helper2.setup.ToolGuideTtls;
import ai.helper2.setup.ToolGuideModel;
import ai.helper2.setup.ToolGuides;
import ai.helper2.setup.ToolMaterials;
import io.micrometer.core.instrument.Counter;
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
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SetupConfiguration.SetupProperties.class)
class SetupConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SetupConfiguration.class);

    /**
     * The setup guide's settings ({@code helper2.setup.*}, each an {@code H2_SETUP_*} variable).
     *
     * @param documentTimeout  per document fetch
     * @param npmReadmeBaseUrl where npm READMEs are read (jsDelivr)
     * @param cacheTtl         how long registry entries and documents are kept in memory
     * @param documentCacheMb  the most document text kept in memory per replica
     * @param hourlyFetches    documents fetched per UTC hour across replicas (shares the Scout's GitHub token)
     * @param guide            the step-by-step guide's model settings
     */
    @ConfigurationProperties("helper2.setup")
    record SetupProperties(Duration documentTimeout, String npmReadmeBaseUrl, Duration cacheTtl, int documentCacheMb,
                           int hourlyFetches, Guide guide) {

        /**
         * [dailyModelCalls] the guide's own cap; [cacheTtl] how long answers stay in Redis; [unreadableTtl] how long the
         * "unavailable" answer after two unreadable model answers stays there.
         */
        record Guide(int dailyModelCalls, boolean reasoning, Duration cacheTtl, Duration unreadableTtl) {
        }
    }

    /** Same registry and timeout as the Scout's collector; read per request, so a slow registry fails fast. */
    @Bean
    McpServerSpecs mcpServerSpecs(@Value("${helper2.sources.mcp-registry.base-url}") String baseUrl,
                                  @Value("${helper2.sources.mcp-registry.timeout:10s}") Duration timeout,
                                  SetupProperties setup, ObservationRegistry observations, ObjectMapper json, Clock clock) {
        return new RegistryMcpServerSpecs(ObservedHttp.restClientBuilder(timeout, observations), baseUrl, json, clock, setup.cacheTtl());
    }

    /** The tool's own documents, from the hosts the sources already use (same tokens), under an hourly budget. */
    @Bean
    ToolMaterials toolMaterials(@Value("${helper2.sources.github.base-url:https://api.github.com}") String github,
                                @Value("${helper2.sources.github.token:}") String githubToken,
                                @Value("${helper2.sources.huggingface.base-url:https://huggingface.co}") String hf,
                                @Value("${helper2.sources.huggingface.token:}") String hfToken,
                                SetupProperties setup, ObservationRegistry observations, Clock clock,
                                ObjectProvider<StringRedisTemplate> redis, Resilience resilience, MeterRegistry meters) {
        HourlyFetchBudget budget = new HourlyFetchBudget(setup.hourlyFetches(), clock, redis.getIfAvailable(), resilience,
            Counter.builder("helper2.setup.fetches.refused")
                .description("Setup guide document fetches turned down by the hourly budget").register(meters));
        return new HttpToolMaterials(ObservedHttp.restClientBuilder(setup.documentTimeout(), observations),
            new HttpToolMaterials.Hosts(github, setup.npmReadmeBaseUrl(), hf, githubToken, hfToken), clock, budget,
            new HttpToolMaterials.CacheSettings(setup.cacheTtl(), setup.documentCacheMb() * 1024L * 1024L));
    }

    @Bean
    ReadmeConfigs readmeConfigs(ToolMaterials materials, ObjectMapper json) {
        return new ReadmeMcpConfigs(materials, json);
    }

    @Bean
    SetupGuides setupGuides(McpServerSpecs specs, ReadmeConfigs readme) {
        return new SetupGuides(specs, readme);
    }

    /** The tool guide's own daily model-call cap, apart from the guide's and the Scout's. */
    @Bean
    DailyBudget toolGuideBudget(Clock clock, CallCounter budgetCounter, MeterRegistry meters, SetupProperties setup) {
        DailyBudget budget = new DailyBudget(setup.guide().dailyModelCalls(), clock, "tool-guide", budgetCounter);
        Gauge.builder("helper2.tool_guide.budget.remaining", budget, DailyBudget::remaining)
            .description("Tool guide model calls left under today's cap (UTC day)")
            .register(meters);
        return budget;
    }

    @Bean
    ToolGuides toolGuides(ToolMaterials materials, ObjectProvider<ChatModel> chatModel, LlmSettings llm, DailyBudget toolGuideBudget,
                          ModelCallMetrics metrics, ObjectProvider<StringRedisTemplate> redis, ObjectMapper json, Clock clock, Resilience resilience,
                          @Value("${helper2.llm.native-schema:true}") boolean nativeSchema, SetupProperties setup) {
        ChatModel model = chatModel.getIfAvailable();
        ToolGuideModel guideModel = null;
        Duration cacheTtl = setup.guide().cacheTtl();
        if (llm.usable(model)) {
            String name = setup.guide().reasoning() && llm.hasReasoningModel() ? llm.reasoningModel() : llm.fast();
            guideModel = new SpringAiToolGuideModel(model, name, nativeSchema, toolGuideBudget, metrics, resilience);
            log.info("Tool guide enabled: model={}, cache {}", name, cacheTtl);
        } else {
            log.info("Tool guide without a model: Guide me shows registry and README config examples only");
        }
        StringRedisTemplate template = redis.getIfAvailable();
        ToolGuideCache cache = template == null ? ToolGuideCache.Companion.getNONE() : new RedisToolGuideCache(template, json, resilience);
        return new ToolGuides(materials, guideModel, cache, clock, new ToolGuideTtls(cacheTtl, setup.guide().unreadableTtl()));
    }
}
