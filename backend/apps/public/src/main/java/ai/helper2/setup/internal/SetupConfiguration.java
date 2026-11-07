package ai.helper2.setup.internal;

import ai.helper2.platform.cache.SharedCaches;
import ai.helper2.platform.config.LlmProperties;
import ai.helper2.platform.http.ObservedHttp;
import ai.helper2.platform.llm.CallCounter;
import ai.helper2.platform.llm.DailyBudget;
import ai.helper2.platform.llm.LlmSettings;
import ai.helper2.platform.llm.ModelCallMetrics;
import ai.helper2.platform.resilience.Resilience;
import ai.helper2.platform.settings.Setting;
import ai.helper2.platform.settings.SettingsGroup;
import ai.helper2.setup.McpServerSpecs;
import ai.helper2.setup.ReadmeConfigs;
import ai.helper2.setup.SetupGuides;
import ai.helper2.setup.ToolGuideCache;
import ai.helper2.setup.ToolGuideModel;
import ai.helper2.setup.ToolGuideTtls;
import ai.helper2.setup.ToolGuides;
import ai.helper2.setup.ToolMaterials;
import ai.helper2.setup.config.SetupProperties;
import io.micrometer.core.instrument.Counter;
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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
class SetupConfiguration {

  private static final Logger log = LoggerFactory.getLogger(SetupConfiguration.class);

  /**
   * Same registry and timeout as the Scout's collector; read per request, so a slow registry fails
   * fast.
   */
  @Bean
  McpServerSpecs mcpServerSpecs(
      SetupProperties setup, ObservationRegistry observations, ObjectMapper json, Clock clock) {
    return new RegistryMcpServerSpecs(
        ObservedHttp.restClientBuilder(setup.mcpRegistry().timeout(), observations),
        setup.mcpRegistry().baseUrl().toString(),
        json,
        clock,
        setup.cacheTtl());
  }

  /**
   * The tool's own documents, from the hosts the sources already use, under an hourly budget.
   * GitHub is read with the setup guide's own token, so README fetches never spend the Scout's
   * quota.
   */
  @Bean
  ToolMaterials toolMaterials(
      SetupProperties setup,
      ObservationRegistry observations,
      Clock clock,
      ObjectProvider<StringRedisTemplate> redis,
      Resilience resilience,
      MeterRegistry meters) {
    HourlyFetchBudget budget =
        new HourlyFetchBudget(
            setup.hourlyFetches(),
            clock,
            redis.getIfAvailable(),
            resilience,
            Counter.builder("helper2.setup.fetches.refused")
                .description("Setup guide document fetches turned down by the hourly budget")
                .register(meters));
    return new HttpToolMaterials(
        ObservedHttp.restClientBuilder(setup.documentTimeout(), observations),
        new HttpToolMaterials.Hosts(
            setup.hosts().github().toString(),
            setup.npmReadmeBaseUrl().toString(),
            setup.hosts().huggingface().toString(),
            setup.githubToken(),
            setup.hosts().hfToken()),
        clock,
        budget,
        new HttpToolMaterials.CacheSettings(setup.cacheTtl(), setup.documentCache().toBytes()));
  }

  @Bean
  ReadmeConfigs readmeConfigs(ToolMaterials materials, ObjectMapper json) {
    return new ReadmeMcpConfigs(materials, json);
  }

  @Bean
  SetupGuides setupGuides(McpServerSpecs specs, ReadmeConfigs readme) {
    return new SetupGuides(specs, readme);
  }

  /** The setup guide's settings for the back office's Settings page (ADR 0026). */
  @Bean
  SettingsGroup setupSettings(
      SetupProperties setup, LlmSettings llm, ObjectProvider<ChatModel> chatModel) {
    String model =
        !llm.usable(chatModel.getIfAvailable())
            ? null
            : setup.guide().reasoning() && llm.hasReasoningModel()
                ? llm.reasoningModel()
                : llm.fast();
    return new SettingsGroup(
        "setup",
        "Setup guide",
        SettingsGroup.SETUP,
        List.of(
            Setting.count(
                "setup.dailyModelCalls",
                "Model calls a day",
                setup.guide().dailyModelCalls(),
                "calls a day",
                "H2_SETUP_GUIDE_DAILY_MODEL_CALLS"),
            Setting.model("setup.model", "Setup guide model", model, null),
            Setting.fixedCount(
                "setup.hourlyFetches",
                "Documents fetched an hour",
                setup.hourlyFetches(),
                "fetches an hour")));
  }

  /** The tool guide's own daily model-call cap, apart from the guide's and the Scout's. */
  @Bean
  DailyBudget toolGuideBudget(
      Clock clock, CallCounter budgetCounter, MeterRegistry meters, SetupProperties setup) {
    DailyBudget budget =
        new DailyBudget(setup.guide().dailyModelCalls(), clock, "tool-guide", budgetCounter);
    Gauge.builder("helper2.tool_guide.budget.remaining", budget, DailyBudget::remaining)
        .description("Tool guide model calls left under today's cap (UTC day)")
        .register(meters);
    return budget;
  }

  @Bean
  ToolGuides toolGuides(
      ToolMaterials materials,
      ObjectProvider<ChatModel> chatModel,
      LlmSettings llm,
      DailyBudget toolGuideBudget,
      ModelCallMetrics metrics,
      SharedCaches caches,
      ObjectMapper json,
      Clock clock,
      Resilience resilience,
      LlmProperties llmProperties,
      SetupProperties setup) {
    boolean nativeSchema = llmProperties.nativeSchema();
    ChatModel model = chatModel.getIfAvailable();
    ToolGuideModel guideModel = null;
    Duration cacheTtl = setup.guide().cacheTtl();
    if (llm.usable(model)) {
      String name =
          setup.guide().reasoning() && llm.hasReasoningModel() ? llm.reasoningModel() : llm.fast();
      guideModel =
          new SpringAiToolGuideModel(
              model, name, nativeSchema, toolGuideBudget, metrics, resilience);
      log.info("Tool guide enabled: model={}, cache {}", name, cacheTtl);
    } else {
      log.info(
          "Tool guide without a model: Guide me shows registry and README config examples only");
    }
    ToolGuideCache cache = new SharedToolGuideCache(caches.named("tool-guide"), json);
    return new ToolGuides(
        materials,
        guideModel,
        cache,
        clock,
        new ToolGuideTtls(cacheTtl, setup.guide().unreadableTtl()));
  }
}
