package ai.searchavo.sources.config.scout;

import ai.searchavo.platform.config.HttpClientProperties;
import ai.searchavo.platform.llm.CallCounter;
import ai.searchavo.platform.llm.DailyBudget;
import ai.searchavo.platform.resilience.Resilience;
import ai.searchavo.research.config.ResearchProperties;
import ai.searchavo.scout.PageReader;
import ai.searchavo.search.ShadowLibraries;
import ai.searchavo.shared.CollectorIds;
import ai.searchavo.sources.config.CollectorProperties;
import ai.searchavo.sources.config.SourcesProperties;
import ai.searchavo.sources.liveweb.scout.ScoutLiveWebCredits;
import ai.searchavo.sources.research.CheckUrlTool;
import ai.searchavo.sources.research.FindPackageTool;
import ai.searchavo.sources.research.GitHubRepoTool;
import ai.searchavo.sources.research.HuggingFaceTool;
import ai.searchavo.sources.research.ReadPageTool;
import ai.searchavo.sources.research.WebSearchTool;
import ai.searchavo.sources.support.HttpClients;
import ai.searchavo.sources.support.SourceSettings;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class ResearchToolsConfiguration {

  @Bean
  @Nullable WebSearchTool webSearchTool(
      SourcesProperties p,
      ScoutLiveWebCredits credits,
      Resilience resilience,
      Clock clock,
      ObservationRegistry observations,
      HttpClientProperties http,
      ShadowLibraries shadowLibraries) {
    SourceSettings s = p.get(DiscoveryCollectorRegistrar.SETTINGS);
    if (!s.enabled() || !s.hasToken()) {
      return null;
    }
    return new WebSearchTool(
        HttpClients.client(CollectorsConfiguration.collectorClient(s, observations, http), s),
        credits,
        resilience,
        clock,
        shadowLibraries);
  }

  @Bean
  FindPackageTool findPackageTool(
      SourcesProperties p,
      CollectorProperties.GitHubSdks sdks,
      Clock clock,
      Resilience resilience,
      ObservationRegistry observations,
      HttpClientProperties http) {
    return new FindPackageTool(
        CollectorsConfiguration.depsDevClient(
            p.get(CollectorIds.GITHUB_SDKS), sdks, clock, resilience, observations, http));
  }

  @Bean
  GitHubRepoTool gitHubRepoTool(
      SourcesProperties p,
      Resilience resilience,
      Clock clock,
      ObservationRegistry observations,
      HttpClientProperties http) {
    SourceSettings s = p.get("github");
    return new GitHubRepoTool(
        HttpClients.gitHubClient(CollectorsConfiguration.collectorClient(s, observations, http), s),
        resilience,
        clock);
  }

  @Bean
  HuggingFaceTool huggingFaceTool(
      SourcesProperties p,
      Resilience resilience,
      Clock clock,
      ObservationRegistry observations,
      HttpClientProperties http) {
    SourceSettings s = p.get("huggingface");
    return new HuggingFaceTool(
        HttpClients.client(CollectorsConfiguration.collectorClient(s, observations, http), s),
        resilience,
        clock);
  }

  @Bean
  @Nullable ReadPageTool readPageTool(
      ObjectProvider<PageReader> reader,
      ResearchProperties research,
      Clock clock,
      CallCounter counter,
      ShadowLibraries shadowLibraries) {
    PageReader r = reader.getIfAvailable();
    if (r == null) {
      return null;
    }
    return new ReadPageTool(
        r,
        new DailyBudget(research.dailyPageReads(), clock, "review-research-page-reads", counter),
        shadowLibraries);
  }

  @Bean
  CheckUrlTool checkUrlTool(ShadowLibraries shadowLibraries, ResearchProperties research) {
    return new CheckUrlTool(shadowLibraries, research.checkUrlDeadline());
  }
}
