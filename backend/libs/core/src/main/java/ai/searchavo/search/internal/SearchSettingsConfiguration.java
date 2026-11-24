package ai.searchavo.search.internal;

import ai.searchavo.platform.llm.LlmSettings;
import ai.searchavo.platform.llm.ModelCallMetrics;
import ai.searchavo.platform.settings.Setting;
import ai.searchavo.platform.settings.SettingsGroup;
import ai.searchavo.search.QueryTranslator;
import ai.searchavo.search.SearchSource;
import ai.searchavo.search.SourceDescriptor;
import ai.searchavo.search.config.SearchProperties;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
                "search.sourcesOn", "Sources on", enabledSources, "SA_SOURCE_<ID>_ENABLED"),
            Setting.flag("search.translationOn", "Query translation on", translating, null),
            Setting.count(
                "search.dailyTranslations",
                "Translations a day",
                search.translation().dailyCalls(),
                "calls a day",
                "SA_SEARCH_DAILY_TRANSLATIONS"),
            Setting.model("search.translationModel", "Translation model", translationModel, null),
            Setting.version(
                "search.translationPrompt", "Translation prompt version", translationPrompt),
            Setting.cap(
                "search.webGlobalDailyCredits",
                "Live web credits a day, all of searchavo.ai",
                quota.globalDailyCredits(),
                "credits a day",
                "SA_LIVE_WEB_DAILY_CREDITS"),
            Setting.count(
                "search.webGuestSearch",
                "Web searches a day per visitor, search",
                quota.guest().search(),
                "searches a day",
                "SA_LIVE_WEB_GUEST_SEARCH"),
            Setting.count(
                "search.webGuestGuide",
                "Web searches a day per visitor, guide",
                quota.guest().guide(),
                "searches a day",
                "SA_LIVE_WEB_GUEST_GUIDE")));
  }
}
