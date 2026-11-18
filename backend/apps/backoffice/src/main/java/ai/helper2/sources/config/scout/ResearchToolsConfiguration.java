package ai.helper2.sources.config.scout;

import ai.helper2.platform.config.HttpClientProperties;
import ai.helper2.platform.llm.CallCounter;
import ai.helper2.platform.llm.DailyBudget;
import ai.helper2.platform.resilience.Resilience;
import ai.helper2.research.config.ResearchProperties;
import ai.helper2.scout.PageReader;
import ai.helper2.search.ShadowLibraries;
import ai.helper2.shared.CollectorIds;
import ai.helper2.sources.config.CollectorProperties;
import ai.helper2.sources.config.SourcesProperties;
import ai.helper2.sources.liveweb.scout.ScoutLiveWebCredits;
import ai.helper2.sources.research.CheckUrlTool;
import ai.helper2.sources.research.FindPackageTool;
import ai.helper2.sources.research.GitHubRepoTool;
import ai.helper2.sources.research.HuggingFaceTool;
import ai.helper2.sources.research.ReadPageTool;
import ai.helper2.sources.research.WebSearchTool;
import ai.helper2.sources.support.HttpClients;
import ai.helper2.sources.support.SourceSettings;
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
  CheckUrlTool checkUrlTool(ShadowLibraries shadowLibraries) {
    return new CheckUrlTool(shadowLibraries);
  }
}
