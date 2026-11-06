package ai.helper2.search.internal;

import ai.helper2.platform.llm.LlmSettings;
import ai.helper2.platform.llm.ModelCallMetrics;
import ai.helper2.platform.settings.Setting;
import ai.helper2.platform.settings.SettingsGroup;
import ai.helper2.search.QueryTranslator;
import ai.helper2.search.SearchSource;
import ai.helper2.search.SourceDescriptor;
import ai.helper2.search.config.SearchProperties;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Search's settings for the back office's Settings page (ADR 0045). */
@Configuration(proxyBeanMethods = false)
class SearchSettingsConfiguration {

  @Bean
  SettingsGroup searchSettings(
      SearchProperties search,
      LlmSettings llm,
      ObjectProvider<QueryTranslator> queryTranslator,
      List<SearchSource> sources) {
    boolean translating =
        queryTranslator.getIfAvailable(() -> QueryTranslator.NONE) != QueryTranslator.NONE;
    return searchSettingsGroup(
        search,
        translating,
        translating ? llm.fast() : null,
        translating ? ModelCallMetrics.promptVersion(SpringAiQueryTranslator.systemPrompt()) : null,
        sources.stream()
            .map(SearchSource::getDescriptor)
            .filter(SourceDescriptor::getEnabled)
            .map(SourceDescriptor::getId)
            .toList());
  }

  /**
   * [translating]: a translator runs. The flag shows that, not the property alone: the property can
   * be on while no model is set, and then nothing is translated.
   */
  static SettingsGroup searchSettingsGroup(
      SearchProperties search,
      boolean translating,
      @Nullable String translationModel,
      @Nullable String translationPrompt,
      List<String> enabledSources) {
    SearchProperties.Quota quota = search.web().quota();
    return new SettingsGroup(
        "search",
        "Search",
        SettingsGroup.SEARCH,
        List.of(
            Setting.names(
                "search.sourcesOn", "Sources on", enabledSources, "H2_SOURCE_<ID>_ENABLED"),
            Setting.flag("search.translationOn", "Query translation on", translating, null),
            Setting.count(
                "search.dailyTranslations",
                "Translations a day",
                search.translation().dailyCalls(),
                "calls a day",
                "H2_SEARCH_DAILY_TRANSLATIONS"),
            Setting.model("search.translationModel", "Translation model", translationModel, null),
            Setting.version(
                "search.translationPrompt", "Translation prompt version", translationPrompt),
            Setting.cap(
                "search.webGlobalDailyCredits",
                "Tavily credits a day, all of Helper2",
                quota.globalDailyCredits(),
                "credits a day",
                "H2_TAVILY_GLOBAL_DAILY_CREDITS"),
            Setting.count(
                "search.webGuestSearch",
                "Web searches a day per guest, search",
                quota.guest().search(),
                "searches a day",
                "H2_TAVILY_GUEST_SEARCH"),
            Setting.count(
                "search.webGuestGuide",
                "Web searches a day per guest, guide",
                quota.guest().guide(),
                "searches a day",
                "H2_TAVILY_GUEST_GUIDE"),
            Setting.flag(
                "search.registeredTierOn",
                "Signed-in limits on",
                quota.registeredTierEnabled(),
                "H2_TAVILY_REGISTERED_TIER_ENABLED"),
            Setting.fixedCount(
                "search.webRegisteredSearch",
                "Web searches a day per signed-in user, search",
                quota.registered().search(),
                "searches a day"),
            Setting.fixedCount(
                "search.webRegisteredGuide",
                "Web searches a day per signed-in user, guide",
                quota.registered().guide(),
                "searches a day")));
  }
}
