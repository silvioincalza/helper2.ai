package ai.helper2.setup.internal;

import ai.helper2.platform.http.ObservedHttp;
import ai.helper2.platform.llm.CallCounter;
import ai.helper2.platform.llm.DailyBudget;
import ai.helper2.platform.llm.LlmSettings;
import ai.helper2.platform.llm.ModelCallMetrics;
import ai.helper2.setup.McpServerSpecs;
import ai.helper2.setup.ReadmeConfigs;
import ai.helper2.setup.SetupGuides;
import ai.helper2.setup.ToolGuideCache;
import ai.helper2.setup.ToolGuideModel;
import ai.helper2.setup.ToolGuides;
import ai.helper2.setup.ToolMaterials;
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
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
class SetupConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SetupConfiguration.class);

    /** Same registry and timeout as the Scout's collector; read per request, so a slow registry fails fast. */
    @Bean
    McpServerSpecs mcpServerSpecs(@Value("${helper2.sources.mcp-registry.base-url}") String baseUrl,
                                  @Value("${helper2.sources.mcp-registry.timeout:10s}") Duration timeout,
                                  ObservationRegistry observations, ObjectMapper json, Clock clock) {
        return new RegistryMcpServerSpecs(ObservedHttp.restClientBuilder(timeout, observations), baseUrl, json, clock);
    }

    /** The tool's own documents, from the hosts the sources already use (same tokens). */
    @Bean
    ToolMaterials toolMaterials(@Value("${helper2.sources.github.base-url:https://api.github.com}") String github,
                                @Value("${helper2.sources.github.token:}") String githubToken,
                                @Value("${helper2.sources.huggingface.base-url:https://huggingface.co}") String hf,
                                @Value("${helper2.sources.huggingface.token:}") String hfToken,
                                @Value("${helper2.setup.npm-readme-base-url:https://cdn.jsdelivr.net}") String npm,
                                @Value("${helper2.setup.document-timeout:10s}") Duration timeout,
                                ObservationRegistry observations, Clock clock) {
        return new HttpToolMaterials(ObservedHttp.restClientBuilder(timeout, observations), github, npm, hf, githubToken, hfToken, clock);
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
    DailyBudget toolGuideBudget(Clock clock, CallCounter budgetCounter, MeterRegistry meters,
                                @Value("${helper2.setup.guide.daily-model-calls:300}") int dailyModelCalls) {
        DailyBudget budget = new DailyBudget(dailyModelCalls, clock, "tool-guide", budgetCounter);
        Gauge.builder("helper2.tool_guide.budget.remaining", budget, DailyBudget::remaining)
            .description("Tool guide model calls left under today's cap (UTC day)")
            .register(meters);
        return budget;
    }

    @Bean
    ToolGuides toolGuides(ToolMaterials materials, ObjectProvider<ChatModel> chatModel, LlmSettings llm, DailyBudget toolGuideBudget,
                          ModelCallMetrics metrics, ObjectProvider<StringRedisTemplate> redis, ObjectMapper json, Clock clock,
                          @Value("${helper2.llm.native-schema:true}") boolean nativeSchema,
                          @Value("${helper2.setup.guide.reasoning:false}") boolean reasoning,
                          @Value("${helper2.setup.guide.cache-ttl:P7D}") Duration cacheTtl) {
        ChatModel model = chatModel.getIfAvailable();
        ToolGuideModel guideModel = null;
        if (llm.usable(model)) {
            String name = reasoning && llm.hasReasoningModel() ? llm.reasoningModel() : llm.fast();
            guideModel = new SpringAiToolGuideModel(model, name, nativeSchema, toolGuideBudget, metrics);
            log.info("Tool guide enabled: model={}, cache {}", name, cacheTtl);
        } else {
            log.info("Tool guide without a model: Guide me shows registry and README config examples only");
        }
        StringRedisTemplate template = redis.getIfAvailable();
        ToolGuideCache cache = template == null ? ToolGuideCache.Companion.getNONE() : new RedisToolGuideCache(template, json, clock, cacheTtl);
        return new ToolGuides(materials, guideModel, cache, clock);
    }
}
