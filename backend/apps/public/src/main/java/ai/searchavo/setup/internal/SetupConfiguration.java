package ai.searchavo.setup.internal;

import ai.searchavo.platform.cache.SharedCaches;
import ai.searchavo.platform.config.HttpClientProperties;
import ai.searchavo.platform.config.LlmProperties;
import ai.searchavo.platform.http.ObservedHttp;
import ai.searchavo.platform.llm.CallCounter;
import ai.searchavo.platform.llm.DailyBudget;
import ai.searchavo.platform.llm.LlmSettings;
import ai.searchavo.platform.llm.ModelCallMetrics;
import ai.searchavo.platform.quota.ClientBudgets;
import ai.searchavo.platform.quota.ClientDayQuota;
import ai.searchavo.platform.quota.QuotaKeys;
import ai.searchavo.platform.resilience.Resilience;
import ai.searchavo.platform.settings.Setting;
import ai.searchavo.platform.settings.SettingsGroup;
import ai.searchavo.search.ShadowLibraries;
import ai.searchavo.setup.McpServerSpecs;
import ai.searchavo.setup.ReadmeConfigs;
import ai.searchavo.setup.SetupGuides;
import ai.searchavo.setup.ToolGuideCache;
import ai.searchavo.setup.ToolGuideModel;
import ai.searchavo.setup.ToolGuideTtls;
import ai.searchavo.setup.ToolGuideValidator;
import ai.searchavo.setup.ToolGuides;
import ai.searchavo.setup.ToolMaterials;
import ai.searchavo.setup.config.SetupProperties;
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

  @Bean
  McpServerSpecs mcpServerSpecs(
      SetupProperties setup,
      ObservationRegistry observations,
      HttpClientProperties http,
      ObjectMapper json,
      Clock clock) {
    return new RegistryMcpServerSpecs(
        ObservedHttp.restClientBuilder(setup.mcpRegistry().timeout(), observations, http),
        setup.mcpRegistry().baseUrl().toString(),
        json,
        clock,
        setup.cacheTtl());
  }

  @Bean
  ToolMaterials toolMaterials(
      SetupProperties setup,
      ObservationRegistry observations,
      HttpClientProperties http,
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
            Counter.builder("searchavo.setup.fetches.refused")
                .description("Setup guide document fetches turned down by the hourly budget")
                .register(meters));
    return new HttpToolMaterials(
        ObservedHttp.restClientBuilder(setup.documentTimeout(), observations, http),
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
                "SA_SETUP_GUIDE_DAILY_MODEL_CALLS"),
            Setting.count(
                "setup.clientDailyGuides",
                "New guides a day per visitor",
                setup.guide().clientDailyGuides(),
                "guides a day",
                "SA_SETUP_GUIDE_CLIENT_DAILY"),
            Setting.count(
                "setup.networkDailyGuides",
                "New guides a day per network (IPv4 /24, IPv6 /56)",
                setup.guide().networkDailyGuides(),
                "guides a day",
                "SA_SETUP_GUIDE_NETWORK_DAILY"),
            Setting.names(
                "setup.stepText",
                "Step texts (VERIFIED or SOURCE_ONLY)",
                List.of(setup.guide().stepText().name()),
                "SA_SETUP_GUIDE_STEP_TEXT"),
            Setting.model("setup.model", "Setup guide model", model, null),
            Setting.fixedCount(
                "setup.hourlyFetches",
                "Documents fetched an hour",
                setup.hourlyFetches(),
                "fetches an hour")));
  }

  @Bean
  DailyBudget toolGuideBudget(
      Clock clock, CallCounter budgetCounter, MeterRegistry meters, SetupProperties setup) {
    DailyBudget budget =
        new DailyBudget(setup.guide().dailyModelCalls(), clock, "tool-guide", budgetCounter);
    Gauge.builder("searchavo.tool_guide.budget.remaining", budget, DailyBudget::remaining)
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
      CallCounter budgetCounter,
      QuotaKeys quotaKeys,
      SetupProperties setup,
      ShadowLibraries shadowLibraries) {
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
      log.info(
          "Tool guide enabled: model={}, cache {}, step texts {}",
          name,
          cacheTtl,
          setup.guide().stepText());
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
        new ToolGuideTtls(cacheTtl, setup.guide().unreadableTtl()),
        new ToolGuideValidator(),
        new ClientDayQuota(
            new ClientBudgets(
                "setup-client:",
                setup.guide().clientDailyGuides(),
                clock,
                quotaKeys,
                budgetCounter,
                ClientBudgets.MAX_CLIENTS),
            new ClientBudgets(
                "setup-network:",
                setup.guide().networkDailyGuides(),
                clock,
                quotaKeys,
                budgetCounter,
                ClientBudgets.MAX_CLIENTS)),
        resilience,
        setup.guide().stepText(),
        shadowLibraries);
  }
}
